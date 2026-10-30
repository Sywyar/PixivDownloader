import pathlib,zipfile,hashlib,subprocess,sys,os,re,shutil
root=pathlib.Path(sys.argv[1]).resolve()
src=root/"fault-fixtures"
sources=pathlib.Path(__file__).resolve().parent
host=pathlib.Path(sys.argv[2]).resolve()
sdk=root/"sdk-repository/io/github/sywyar/pixivdownloader"
version=re.search(r"(?m)^version=(.+)$", (host/"META-INF/pixivdownload-sdk.properties").read_text(encoding="utf-8")).group(1).strip()
cp=os.pathsep.join(str(sdk/m/version/(m+"-"+version+".jar")) for m in ["pixivdownload-plugin-api","pixivdownload-core-api","pixivdownload-sdk-info"])
cp+=os.pathsep+str(host/"BOOT-INF/lib/*")
def compile_one(name,out,extra=""):
    out.mkdir(parents=True,exist_ok=True)
    subprocess.run(["javac","-proc:none","-encoding","UTF-8","-cp",cp+extra,"-d",str(out),str(sources/name)],check=True)
def jar(out,classes,properties=None,extra=None):
    with zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED) as z:
        for file in classes.rglob("*.class"): z.write(file,file.relative_to(classes).as_posix())
        if properties is not None: z.writestr("plugin.properties",properties)
        for name,data in (extra or {}).items():z.writestr(name,data)
compile_one("Probe.java",src/"api-classes")
jar(src/"probe-api.jar",src/"api-classes")
api=(src/"probe-api.jar").read_bytes()
compile_one("Contract.java",src/"wrapper-classes")
def props(id,cls,extra=""):
    return "plugin.id="+id+"\nplugin.version=0.1.0\nplugin.requires="+version+"\nplugin.class="+cls+"\nplugin.provider=Acceptance\nplugin.description=Isolated fixture\npixiv.kind=feature\npixiv.execution-mode=host-process-full-trust\n"+extra
jar(src/"probe-contract.jar",src/"wrapper-classes",props("probe-contract","acceptance.wrapper.Contract","pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/probe-api.jar\npixiv.contract-sha256="+hashlib.sha256(api).hexdigest()+"\n"),{"lib/probe-api.jar":api})
compile_one("Participant.java",src/"participant-classes",os.pathsep+str(src/"probe-api.jar"))
for id in ["probe-provider","probe-provider-two","probe-provider-broken","probe-consumer"]:
    config="Consumer" if id=="probe-consumer" else "Provider"
    jar(src/(id+".jar"),src/"participant-classes",props(id,"acceptance.participant.Participant","pixiv.lifecycle-policy=hot-reload\nplugin.dependencies=probe-contract@0.1.0\npixiv.configuration-classes=acceptance.participant.Participant$"+config+"\n"))
print("Independent fixtures built against fixed SDK")

# 只在宿主及 SDK 已冻结后编译全新远程业务契约及两端插件。
tools=pathlib.Path(sys.argv[3]).resolve()
remote=root/"remote-contract"
java=remote/"src/main/java/acceptance/remotecontract/RemoteLedger.java"
java.parent.mkdir(parents=True,exist_ok=True)
shutil.copyfile(sources/"RemoteLedger.java",java)
encoded=java.parent/"encoded/RemoteLedger.java"
encoded.parent.mkdir(parents=True,exist_ok=True)
shutil.copyfile(sources/"encoded/RemoteLedger.java",encoded)
subprocess.run(["java","-jar",str(tools),"schema",str(remote),cp],check=True)
compile_one("RemoteLedger.java",src/"remote-api-classes")
compile_one("encoded/RemoteLedger.java",src/"remote-api-classes")
schemas=remote/"target/classes"
extra={f.relative_to(schemas).as_posix():f.read_bytes() for f in schemas.rglob("*") if f.is_file()}
jar(src/"remote-ledger-api.jar",src/"remote-api-classes",extra=extra)
api=(src/"remote-ledger-api.jar").read_bytes()
jar(src/"remote-ledger-contract.jar",src/"wrapper-classes",props("remote-ledger-contract","acceptance.wrapper.Contract",
    "pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/remote-ledger-api.jar\npixiv.contract-sha256="+hashlib.sha256(api).hexdigest()+"\n"),{"lib/remote-ledger-api.jar":api})
remote_consumer=root/"remote-consumer"
remote_consumer.mkdir(parents=True,exist_ok=True)
# JavaCompiler 需要展开的 JAR 列表，而不是 java 启动器的通配符。
remote_cp=cp.replace(str(host/"BOOT-INF/lib/*"),os.pathsep.join(str(p) for p in sorted((host/"BOOT-INF/lib").glob("*.jar"))))
remote_cp+=os.pathsep+str(src/"remote-ledger-api.jar")
subprocess.run(["java","-jar",str(tools),"remote",str(remote_consumer),remote_cp,"acceptance.remotecontract.RemoteLedger","acceptance.remotecontract.encoded.RemoteLedger"],check=True)
compile_one("RemoteParticipant.java",src/"remote-participant-classes",os.pathsep+str(src/"remote-ledger-api.jar")+os.pathsep+str(remote_consumer/"target/classes"))
generated=remote_consumer/"target/classes"
extra={f.relative_to(generated).as_posix():f.read_bytes() for f in generated.rglob("*") if f.is_file()}
for id in ["remote-ledger-provider","remote-ledger-consumer"]:
    config="Provider" if id.endswith("provider") else "Consumer"
    jar(src/(id+".jar"),src/"remote-participant-classes",props(id,"acceptance.remote.RemoteParticipant",
        "pixiv.lifecycle-policy=hot-reload\nplugin.dependencies=remote-ledger-contract@0.1.0\npixiv.configuration-classes=acceptance.remote.RemoteParticipant$"+config+"\n"),extra)
print("Independent remote contract and generated adapter compiled against fixed SDK and host")
bad=remote/"Bad.java"
bad.write_text('import acceptance.remotecontract.RemoteLedger; class Bad { void wrong(RemoteLedger service) { service.record(true); } }',encoding="utf-8")
rejected=subprocess.run(["javac","-proc:none","-encoding","UTF-8","-cp",remote_cp,str(bad)],capture_output=True)
if rejected.returncode==0: raise RuntimeError("wrong Java consumer type compiled")
tsc=pathlib.Path(sys.argv[4]).resolve()
probe=generated/"web/type-probe.mts"
probe.write_text('import {createClient, type __proto___Request, type routes_Request} from "./generated/acceptance.remotecontract.RemoteLedger.mjs";\ncreateClient().record({sequence:1,text:"ok",numbers:[],labels:{},mode:"NORMAL"});\nconst named: __proto___Request & routes_Request = {["__proto__"]:"safe"};\ncreateClient().__proto__(named);\ncreateClient().routes(named);\n',encoding="utf-8")
arguments=["node",str(tsc),"--noEmit","--strict","--target","es2022","--module","nodenext",str(probe)]
probe.write_text(probe.read_text(encoding="utf-8")+'''import {createClient as valuesClient, wireValues as w} from "./generated/acceptance.remotecontract.encoded.RemoteLedger.mjs";
valuesClient().roundTrip({sequence:w.int64(9223372036854775807n),id:w.uuid("12345678-1234-5678-1234-567812345678"),
  instant:w.instant(-1n,1),duration:w.duration(1n,1),score:w.float32(-0),ratio:w.float64(Number.MIN_VALUE)});
''',encoding="utf-8")
subprocess.run(arguments,check=True)
probe.write_text(probe.read_text(encoding="utf-8").replace("sequence:1","sequence:\"wrong\""),encoding="utf-8")
rejected=subprocess.run(arguments,capture_output=True)
if rejected.returncode==0 or b"TS2322" not in rejected.stdout: raise RuntimeError("wrong frontend consumer type was not rejected")
probe.unlink()
print("Independent Java and frontend wrong-type consumers rejected by real compilers")

# 业务协作、任务与安全接缝只引用上述冻结的公共 SDK。
compile_one("Business.java",src/"business-api-classes")
jar(src/"business-api.jar",src/"business-api-classes")
api=(src/"business-api.jar").read_bytes()
jar(src/"independent-contract.jar",src/"wrapper-classes",props("independent-contract","acceptance.wrapper.Contract",
    "pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/business-api.jar\npixiv.contract-sha256="+hashlib.sha256(api).hexdigest()+"\n"),
    {"lib/business-api.jar":api})
compile_one("BusinessParticipant.java",src/"business-classes",os.pathsep+str(src/"business-api.jar"))
for role,config in {"storage":"Storage","collector":"Collector","display":"Display",
                    "validator-one":"Validation","validator-two":"ValidationTwo","validator-three":"ValidationThree",
                    "sink-one":"Sink","sink-two":"Sink","jobs":"Jobs"}.items():
    id="independent-"+role
    jar(src/(id+".jar"),src/"business-classes",props(id,"acceptance.businessplugin.BusinessParticipant",
        "pixiv.lifecycle-policy=hot-reload\nplugin.dependencies=independent-contract@0.1.0\n"
        +"pixiv.configuration-classes=acceptance.businessplugin.BusinessParticipant$"+config+"\n"))
compile_one("LoginParticipant.java",src/"login-classes")
for id in ["neutral-login","neutral-login-duplicate"]:
    jar(src/(id+".jar"),src/"login-classes",props(id,"acceptance.login.LoginParticipant",
        "pixiv.lifecycle-policy=hot-reload\npixiv.configuration-classes=acceptance.login.LoginParticipant$Wiring\n"),
        {"proof/proof.js": (sources/"login-proof.js").read_bytes()})
print("Independent storage, collector, display, validators, sinks, jobs and login fixtures built")
