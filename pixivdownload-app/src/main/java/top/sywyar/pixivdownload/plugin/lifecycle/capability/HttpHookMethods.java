package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import top.sywyar.pixivdownload.plugin.security.*;

/** 只在插件准备阶段发现本地单例 Bean 的方法，执行仍经过原受管服务代理。 */
final class HttpHookMethods {
    private static final String EMPTY = "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}";
    record Found(OperationHook hook, HttpHookDeclarations.Declaration declaration, Object bean, boolean replacesHook) { }

    static List<Found> find(ConfigurableApplicationContext context, ExternalCapabilityPreparation preparation,
            ExternalCapabilityInvocationRegistry registry) {
        var found = new ArrayList<Found>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var names = new LinkedHashSet<>(List.of(context.getBeanFactory().getBeanDefinitionNames()));
        names.addAll(List.of(context.getBeanFactory().getSingletonNames()));
        for (String name : names) {
            Class<?> type = context.getBeanFactory().getType(name, false);
            Object singleton = context.getBeanFactory().getSingleton(name);
            if (singleton instanceof org.springframework.beans.factory.FactoryBean<?> && context.isSingleton(name))
                singleton = context.getBean(name);
            if (singleton != null) type = AopUtils.getTargetClass(singleton);
            if (type == null) continue;
            var methods = MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<HttpHook>) method -> AnnotatedElementUtils.findMergedAnnotation(method, HttpHook.class));
            if (methods.isEmpty()) continue;
            if (!context.isSingleton(name)) throw invalid();
            Object bean = context.getBean(name);
            if (!seen.add(bean)) continue;
            Class<?> userType = AopUtils.getTargetClass(bean);
            for (var entry : methods.entrySet()) {
                Method method = entry.getKey(); HttpHook annotation = entry.getValue();
                if (AnnotatedElementUtils.hasAnnotation(userType, org.springframework.scheduling.annotation.Async.class)
                        || AnnotatedElementUtils.hasAnnotation(method, org.springframework.scheduling.annotation.Async.class)) throw invalid();
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.isBridge() || method.getParameterCount() != 1 || method.getParameterTypes()[0] != OperationHook.Invocation.class
                        || method.getReturnType() != OperationHook.Decision.class
                            && !(method.getReturnType() == void.class && annotation.phase() == OperationHook.Phase.AFTER_COMPLETION)) throw invalid();
                Method invocable = MethodIntrospector.selectInvocableMethod(method, bean.getClass());
                if (!invocable.trySetAccessible()) throw invalid();
                boolean replaces = bean instanceof OperationHook && method.getName().equals("invoke");
                OperationHook delegate = replaces ? (OperationHook) bean : null;
                if (delegate != null && !annotation.id().equals(registry.captureMetadata(preparation,
                        OperationHook.class, "id", delegate::id))) throw invalid();
                var target = new HttpHookDeclarations.Target(annotation.targetOwner(), annotation.method(), annotation.path(),
                        List.of(annotation.params()), List.of(annotation.headers()), List.of(annotation.consumes()), List.of(annotation.produces()));
                var fields = Arrays.stream(annotation.fields()).map(field -> new OperationBinding.Field(field.source(), field.path(), field.name())).toList();
                var declaration = new HttpHookDeclarations.Declaration(annotation.id(), userType.getName() + "#" + method.getName(),
                        target, annotation.phase(), annotation.order(), annotation.completion(), annotation.configuration(), fields, annotation.revision());
                OperationHook hook = new MethodHook(bean, invocable, delegate, declaration);
                var definition = registry.captureMetadata(preparation, OperationHook.class, "definition", hook::definition);
                HookContracts.binding(declaration.binding("annotation", "target:pending"), definition);
                if (found.size() == 16) throw invalid();
                found.add(new Found(hook, declaration, bean, replaces));
            }
        }
        return List.copyOf(found);
    }

    private record MethodHook(Object bean, Method method, OperationHook delegate,
            HttpHookDeclarations.Declaration declaration) implements OperationHook {
        public String id() { return declaration.hook(); }
        public String generation() { return delegate == null ? declaration.revision() : delegate.generation(); }
        public Definition definition() {
            var base = delegate == null ? new Definition(declaration.revision(), Set.of(declaration.phase()), EMPTY, "", "", null, List.of())
                    : HookContracts.definition(delegate.definition());
            if (!base.phases().contains(declaration.phase())) throw invalid();
            return HookContracts.definition(new Definition("annotation-" + HttpHookDeclarations.hash(base.revision() + ":" + declaration.contract()),
                    base.phases(), base.configurationSchema(), base.inputSchema(), base.resultSchema(), base.protocol(), base.resources()));
        }
        public Decision invoke(Invocation invocation) {
            try {
                Object result = method.invoke(bean, invocation);
                return method.getReturnType() == void.class ? Decision.proceed() : (Decision) result;
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Error fatal) throw fatal;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("OPERATION_HOOK_CALL_FAILED");
            } catch (IllegalAccessException failure) { throw new IllegalStateException("OPERATION_HOOK_METHOD_UNAVAILABLE"); }
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OPERATION_HOOK_METHOD_INVALID"); }
}
