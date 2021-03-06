/*
 * Copyright 2004-2005 Graeme Rocher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.orm.hibernate.proxy;

import groovy.lang.GroovyObject;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import org.apache.commons.logging.LogFactory;
import org.grails.orm.hibernate.cfg.HibernateUtils;
import org.grails.datastore.mapping.proxy.EntityProxy;
import org.grails.datastore.mapping.proxy.EntityProxyMethodHandler;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.proxy.pojo.BasicLazyInitializer;
import org.hibernate.proxy.pojo.javassist.SerializableProxy;
import org.hibernate.type.CompositeType;

/**
 * @author Graeme Rocher
 * @since 1.0
 */
public class GroovyAwareJavassistLazyInitializer extends BasicLazyInitializer implements MethodHandler {

    private static final String WRITE_CLASSES_DIRECTORY = System.getProperty("javassist.writeDirectory");

    private static final Set<String> GROOVY_METHODS = new HashSet<>(Arrays.asList("$getStaticMetaClass"));

    private static final MethodFilter METHOD_FILTERS = new MethodFilter() {
        public boolean isHandled(Method m) {
            // skip finalize methods
            return m.getName().indexOf("super$") == -1 &&
                !GROOVY_METHODS.contains(m.getName()) &&
                !(m.getParameterTypes().length == 0 && (m.getName().equals("finalize")));
        }
    };

    private final Class<?>[] interfaces;
    private final Class factory;
    private boolean constructed = false;
    HibernateGroovyObjectMethodHandler groovyObjectMethodHandler;

    public GroovyAwareJavassistLazyInitializer(
            final String entityName,
            final Class<?> persistentClass,
            final Class<?>[] interfaces,
            final Serializable id,
            final Method getIdentifierMethod,
            final Method setIdentifierMethod,
            final CompositeType componentIdType,
            final Object session,
            final boolean overridesEquals) {
        super(entityName, persistentClass, id, getIdentifierMethod, setIdentifierMethod, componentIdType, (SessionImplementor)session, overridesEquals);
        this.interfaces = interfaces;
        this.factory = getProxyFactory(persistentClass, interfaces);
    }

    /**
     * @return Creates a proxy
     */
    HibernateProxy createProxy() {

        final HibernateProxy proxy;
        try {
            proxy = (HibernateProxy)factory.newInstance();
        } catch (Exception e) {
            throw new HibernateException("Javassist Enhancement failed: " + persistentClass.getName(), e);
        }
        ((ProxyObject) proxy).setHandler(this);
        groovyObjectMethodHandler = new HibernateGroovyObjectMethodHandler(persistentClass, proxy, this);
        constructed = true;
        return proxy;
    }

    private static Class<?> getProxyFactory(Class<?> persistentClass, Class<?>[] interfaces) throws HibernateException {
        // note: interfaces is assumed to already contain HibernateProxy.class

        try {
            Set<Class<?>> allInterfaces = new HashSet<Class<?>>();
            if(interfaces != null) {
                allInterfaces.addAll(Arrays.asList(interfaces));
            }
            allInterfaces.add(GroovyObject.class);
            allInterfaces.add(EntityProxy.class);
            ProxyFactory factory = createProxyFactory(persistentClass, allInterfaces.toArray(new Class<?>[allInterfaces.size()]));
            Class<?> proxyClass = factory.createClass();
            HibernateUtils.enhanceProxyClass(proxyClass);
            return proxyClass;
        }
        catch (Throwable t) {
            LogFactory.getLog(BasicLazyInitializer.class).error(
                    "Javassist Enhancement failed: " + persistentClass.getName(), t);
            throw new HibernateException("Javassist Enhancement failed: " + persistentClass.getName(), t);
        }
    }

    private static ProxyFactory createProxyFactory(Class<?> persistentClass, Class<?>[] interfaces) {
        ProxyFactory factory = new ProxyFactory();
        factory.setSuperclass(persistentClass);
        factory.setInterfaces(interfaces);
        factory.setFilter(METHOD_FILTERS);
        factory.setUseCache(true);
        if (WRITE_CLASSES_DIRECTORY != null) {
            factory.writeDirectory = WRITE_CLASSES_DIRECTORY;
        }
        return factory;
    }

    public Object invoke(final Object proxy, final Method thisMethod, final Method proceed,
            final Object[] args) throws Throwable {
        // while constructor is running
        if (thisMethod.getName().equals("getHibernateLazyInitializer")) {
            return this;
        }

        Object result = groovyObjectMethodHandler.handleInvocation(proxy, thisMethod, args);
        if (groovyObjectMethodHandler.wasHandled(result)) {
           return result;
        }

        if (constructed) {
            try {
                result = invoke(thisMethod, args, proxy);
            }
            catch (Throwable t) {
                throw new Exception(t.getCause());
            }
            if (result == INVOKE_IMPLEMENTATION) {
                Object target = getImplementation();
                final Object returnValue;
                try {
                    if (ReflectHelper.isPublic(persistentClass, thisMethod)) {
                        if (!thisMethod.getDeclaringClass().isInstance(target)) {
                            throw new ClassCastException(target.getClass().getName());
                        }
                        returnValue = thisMethod.invoke(target, args);
                    }
                    else {
                        if (!thisMethod.isAccessible()) {
                            thisMethod.setAccessible(true);
                        }
                        returnValue = thisMethod.invoke(target, args);
                    }
                    return returnValue == target ? proxy : returnValue;
                }
                catch (InvocationTargetException ite) {
                    throw ite.getTargetException();
                }
            }
            return result;
        }

        return proceed.invoke(proxy, args);
    }

    @Override
    protected Object serializableProxy() {
        return new SerializableProxy(
                getEntityName(),
                persistentClass,
                interfaces,
                getIdentifier(),
                false,
                getIdentifierMethod,
                setIdentifierMethod,
                componentIdType);
    }

    private static class HibernateGroovyObjectMethodHandler extends EntityProxyMethodHandler {
        private Object target;
        private final Object originalSelf;
        private final LazyInitializer lazyInitializer;

        public HibernateGroovyObjectMethodHandler(Class<?> proxyClass, Object originalSelf, LazyInitializer lazyInitializer) {
            super(proxyClass);
            this.originalSelf = originalSelf;
            this.lazyInitializer = lazyInitializer;
        }

        @Override
        protected Object resolveDelegate(Object self) {
            if (self != originalSelf) {
                throw new IllegalStateException("self instance has changed.");
            }
            if (target == null) {
                target = lazyInitializer.getImplementation();
            }
            return target;
        }

        @Override
        protected Object isProxyInitiated(Object self) {
            return target != null || !lazyInitializer.isUninitialized();
        }

        @Override
        protected Object getProxyKey(Object self) {
            return lazyInitializer.getIdentifier();
        }
    }
}
