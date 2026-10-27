import pathlib,zipfile,hashlib,subprocess,sys,os,re
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
    return "plugin.id="+id+"\nplugin.version=0.1.0\nplugin.requires=1.4\nplugin.class="+cls+"\nplugin.provider=Acceptance\nplugin.description=Isolated fixture\npixiv.kind=feature\npixiv.execution-mode=host-process-full-trust\n"+extra
jar(src/"probe-contract.jar",src/"wrapper-classes",props("probe-contract","acceptance.wrapper.Contract","pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/probe-api.jar\npixiv.contract-sha256="+hashlib.sha256(api).hexdigest()+"\n"),{"lib/probe-api.jar":api})
compile_one("Participant.java",src/"participant-classes",os.pathsep+str(src/"probe-api.jar"))
for id in ["probe-provider","probe-provider-two","probe-provider-broken","probe-consumer"]:
    config="Consumer" if id=="probe-consumer" else "Provider"
    jar(src/(id+".jar"),src/"participant-classes",props(id,"acceptance.participant.Participant","pixiv.lifecycle-policy=hot-reload\nplugin.dependencies=probe-contract@0.1.0\npixiv.configuration-classes=acceptance.participant.Participant$"+config+"\n"))
print("Independent fixtures built against fixed SDK")
