/*
 * Copyright 2014 - Present Rafael Winterhalter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bytebuddy.dynamic.loading;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.build.HashCodeAndEqualsPlugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * <p>
 * A {@link java.lang.ClassLoader} that is capable of loading explicitly defined classes. The class loader will free
 * any binary resources once a class that is defined by its binary data is loaded. This class loader is thread safe since
 * the class loading mechanics are only called from synchronized context.
 * </p>
 * <p>
 * <b>Note</b>: Instances of this class loader return URLs for their represented class loaders with the <i>bytebuddy</i> schema.
 * These URLs do not represent URIs as two classes with the same name yield identical URLs but might represents different byte
 * arrays.
 * </p>
 * <p>
 * <b>Note</b>: Any class and package definition is performed using the creator's {@link AccessControlContext}.
 * </p>
 */
public class ByteArrayClassLoader extends InjectionClassLoader {

    /**
     * The schema for URLs that represent a class file of byte array class loaders.
     */
    public static final String URL_SCHEMA = "bytebuddy";

    /**
     * Indicates that an array should be included from its first index. Improves the source code readability.
     */
    private static final int FROM_BEGINNING = 0;

    /**
     * Indicates that a URL does not exist to improve code readability.
     */
    private static final URL NO_URL = null;

    /**
     * A strategy for locating a package by name.
     */
    private static final PackageLookupStrategy PACKAGE_LOOKUP_STRATEGY = AccessController.doPrivileged(PackageLookupStrategy.CreationAction.INSTANCE);

    /**
     * The synchronization engine for the executing JVM.
     */
    protected static final SynchronizationStrategy.Initializable SYNCHRONIZATION_STRATEGY = AccessController.doPrivileged(SynchronizationStrategy.CreationAction.INSTANCE);

    /*
     * Register class loader as parallel capable if the current VM supports it.
     */
    static {
        doRegisterAsParallelCapable();
    }

    /**
     * Registers class loader as parallel capable if possible.
     */
    @SuppressFBWarnings(value = "DP_DO_INSIDE_DO_PRIVILEGED", justification = "Must be invoked from targeting ClassLoader class.")
    private static void doRegisterAsParallelCapable() {
        try {
            Method method = ClassLoader.class.getDeclaredMethod("registerAsParallelCapable");
            method.setAccessible(true);
            method.invoke(null);
        } catch (Throwable ignored) {
            /* do nothing */
        }
    }

    /**
     * A mutable map of type names mapped to their binary representation.
     */
    protected final ConcurrentMap<String, byte[]> typeDefinitions;

    /**
     * The persistence handler of this class loader.
     */
    protected final PersistenceHandler persistenceHandler;

    /**
     * The protection domain to apply. Might be {@code null} when referencing the default protection domain.
     */
    protected final ProtectionDomain protectionDomain;

    /**
     * The package definer to be queried for package definitions.
     */
    protected final PackageDefinitionStrategy packageDefinitionStrategy;

    /**
     * The class file transformer to apply on loaded classes.
     */
    protected final ClassFilePostProcessor classFilePostProcessor;

    /**
     * The access control context to use for loading classes.
     */
    protected final AccessControlContext accessControlContext;

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent          The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param typeDefinitions A map of fully qualified class names pointing to their binary representations.
     */
    public ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> typeDefinitions) {
        this(parent, true, typeDefinitions);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent          The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param sealed          {@code true} if this class loader is sealed.
     * @param typeDefinitions A map of fully qualified class names pointing to their binary representations.
     */
    public ByteArrayClassLoader(ClassLoader parent, boolean sealed, Map<String, byte[]> typeDefinitions) {
        this(parent, sealed, typeDefinitions, PersistenceHandler.LATENT);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent             The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param typeDefinitions    A map of fully qualified class names pointing to their binary representations.
     * @param persistenceHandler The persistence handler of this class loader.
     */
    public ByteArrayClassLoader(ClassLoader parent, Map<String, byte[]> typeDefinitions, PersistenceHandler persistenceHandler) {
        this(parent, true, typeDefinitions, persistenceHandler);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent             The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param sealed             {@code true} if this class loader is sealed.
     * @param typeDefinitions    A map of fully qualified class names pointing to their binary representations.
     * @param persistenceHandler The persistence handler of this class loader.
     */
    public ByteArrayClassLoader(ClassLoader parent, boolean sealed, Map<String, byte[]> typeDefinitions, PersistenceHandler persistenceHandler) {
        this(parent, sealed, typeDefinitions, ClassLoadingStrategy.NO_PROTECTION_DOMAIN, persistenceHandler, PackageDefinitionStrategy.Trivial.INSTANCE);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
     * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
     * @param packageDefinitionStrategy The package definer to be queried for package definitions.
     * @param persistenceHandler        The persistence handler of this class loader.
     */
    public ByteArrayClassLoader(ClassLoader parent,
                                Map<String, byte[]> typeDefinitions,
                                ProtectionDomain protectionDomain,
                                PersistenceHandler persistenceHandler,
                                PackageDefinitionStrategy packageDefinitionStrategy) {
        this(parent, true, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param sealed                    {@code true} if this class loader is sealed.
     * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
     * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
     * @param packageDefinitionStrategy The package definer to be queried for package definitions.
     * @param persistenceHandler        The persistence handler of this class loader.
     */
    public ByteArrayClassLoader(ClassLoader parent,
                                boolean sealed,
                                Map<String, byte[]> typeDefinitions,
                                ProtectionDomain protectionDomain,
                                PersistenceHandler persistenceHandler,
                                PackageDefinitionStrategy packageDefinitionStrategy) {
        this(parent, sealed, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy, ClassFilePostProcessor.NoOp.INSTANCE);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
     * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
     * @param packageDefinitionStrategy The package definer to be queried for package definitions.
     * @param persistenceHandler        The persistence handler of this class loader.
     * @param classFilePostProcessor    A post processor for class files to apply p
     */
    public ByteArrayClassLoader(ClassLoader parent,
                                Map<String, byte[]> typeDefinitions,
                                ProtectionDomain protectionDomain,
                                PersistenceHandler persistenceHandler,
                                PackageDefinitionStrategy packageDefinitionStrategy,
                                ClassFilePostProcessor classFilePostProcessor) {
        this(parent, true, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy, classFilePostProcessor);
    }

    /**
     * Creates a new class loader for a given definition of classes.
     *
     * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
     * @param sealed                    {@code true} if this class loader is sealed.
     * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
     * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
     * @param packageDefinitionStrategy The package definer to be queried for package definitions.
     * @param persistenceHandler        The persistence handler of this class loader.
     * @param classFilePostProcessor    A post processor for class files to apply p
     */
    public ByteArrayClassLoader(ClassLoader parent,
                                boolean sealed,
                                Map<String, byte[]> typeDefinitions,
                                ProtectionDomain protectionDomain,
                                PersistenceHandler persistenceHandler,
                                PackageDefinitionStrategy packageDefinitionStrategy,
                                ClassFilePostProcessor classFilePostProcessor) {
        super(parent, sealed);
        this.typeDefinitions = new ConcurrentHashMap<String, byte[]>(typeDefinitions);
        this.protectionDomain = protectionDomain;
        this.persistenceHandler = persistenceHandler;
        this.packageDefinitionStrategy = packageDefinitionStrategy;
        this.classFilePostProcessor = classFilePostProcessor;
        accessControlContext = AccessController.getContext();
    }

    /**
     * Resolves a method handle in the scope of the {@link ByteArrayClassLoader} class.
     *
     * @return A method handle for this class.
     * @throws Exception If the method handle facility is not supported by the current virtual machine.
     */
    private static Object methodHandle() throws Exception {
        return Class.forName("java.lang.invoke.MethodHandles").getMethod("lookup").invoke(null);
    }

    /**
     * Loads a given set of class descriptions and their binary representations.
     *
     * @param classLoader The parent class loader.
     * @param types       The unloaded types to be loaded.
     * @return A map of the given type descriptions pointing to their loaded representations.
     */
    public static Map<TypeDescription, Class<?>> load(ClassLoader classLoader, Map<TypeDescription, byte[]> types) {
        return load(classLoader,
                types,
                ClassLoadingStrategy.NO_PROTECTION_DOMAIN,
                PersistenceHandler.LATENT,
                PackageDefinitionStrategy.Trivial.INSTANCE,
                false,
                true);
    }

    /**
     * Loads a given set of class descriptions and their binary representations.
     *
     * @param classLoader               The parent class loader.
     * @param types                     The unloaded types to be loaded.
     * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
     * @param persistenceHandler        The persistence handler of the created class loader.
     * @param packageDefinitionStrategy The package definer to be queried for package definitions.
     * @param forbidExisting            {@code true} if the class loading should throw an exception if a class was already loaded by a parent class loader.
     * @param sealed                    {@code true} if the class loader should be sealed.
     * @return A map of the given type descriptions pointing to their loaded representations.
     */
    @SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Privilege is explicit user responsibility")
    public static Map<TypeDescription, Class<?>> load(ClassLoader classLoader,
                                                      Map<TypeDescription, byte[]> types,
                                                      ProtectionDomain protectionDomain,
                                                      PersistenceHandler persistenceHandler,
                                                      PackageDefinitionStrategy packageDefinitionStrategy,
                                                      boolean forbidExisting,
                                                      boolean sealed) {
        Map<String, byte[]> typesByName = new HashMap<String, byte[]>();
        for (Map.Entry<TypeDescription, byte[]> entry : types.entrySet()) {
            typesByName.put(entry.getKey().getName(), entry.getValue());
        }
        classLoader = new ByteArrayClassLoader(classLoader,
                sealed,
                typesByName,
                protectionDomain,
                persistenceHandler,
                packageDefinitionStrategy,
                ClassFilePostProcessor.NoOp.INSTANCE);
        Map<TypeDescription, Class<?>> result = new LinkedHashMap<TypeDescription, Class<?>>();
        for (TypeDescription typeDescription : types.keySet()) {
            try {
                Class<?> type = Class.forName(typeDescription.getName(), false, classLoader);
                if (forbidExisting && type.getClassLoader() != classLoader) {
                    throw new IllegalStateException("Class already loaded: " + type);
                }
                result.put(typeDescription, type);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("Cannot load class " + typeDescription, exception);
            }
        }
        return result;
    }

    @Override
    protected Map<String, Class<?>> doDefineClasses(Map<String, byte[]> typeDefinitions) throws ClassNotFoundException {
        Map<String, byte[]> previous = new HashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : typeDefinitions.entrySet()) {
            previous.put(entry.getKey(), this.typeDefinitions.putIfAbsent(entry.getKey(), entry.getValue()));
        }
        try {
            Map<String, Class<?>> types = new LinkedHashMap<String, Class<?>>();
            for (String name : typeDefinitions.keySet()) {
                synchronized (SYNCHRONIZATION_STRATEGY.initialize().getClassLoadingLock(this, name)) {
                    types.put(name, loadClass(name));
                }
            }
            return types;
        } finally {
            for (Map.Entry<String, byte[]> entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    persistenceHandler.release(entry.getKey(), this.typeDefinitions);
                } else {
                    this.typeDefinitions.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] binaryRepresentation = persistenceHandler.lookup(name, typeDefinitions);
        if (binaryRepresentation == null) {
            throw new ClassNotFoundException(name);
        } else {
            return AccessController.doPrivileged(new ClassDefinitionAction(name, classFilePostProcessor.transform(this,
                    name,
                    protectionDomain,
                    binaryRepresentation)), accessControlContext);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected URL findResource(String name) {
        return persistenceHandler.url(name, typeDefinitions);
    }

    /**
     * {@inheritDoc}
     */
    protected Enumeration<URL> findResources(String name) {
        URL url = persistenceHandler.url(name, typeDefinitions);
        return url == null
                ? EmptyEnumeration.INSTANCE
                : new SingletonEnumeration(url);
    }

    /**
     * Returns the package for a given name.
     *
     * @param name The name of the package.
     * @return A suitable package or {@code null} if no such package exists.
     */
    @SuppressWarnings("deprecation")
    private Package doGetPackage(String name) {
        return getPackage(name);
    }

    /**
     * An engine for receiving a <i>class loading lock</i> when loading a class.
     */
    protected interface SynchronizationStrategy {

        /**
         * Receives the class loading lock.
         *
         * @param name        The name of the class being loaded.
         * @param classLoader The class loader loading the class.
         * @return The corresponding class loading lock.
         */
        Object getClassLoadingLock(ByteArrayClassLoader classLoader, String name);

        /**
         * An uninitialized synchronization strategy.
         */
        interface Initializable {

            /**
             * Initializes this synchronization strategy.
             *
             * @return The synchronization strategy to use.
             */
            SynchronizationStrategy initialize();
        }

        /**
         * A creation action for a synchronization strategy.
         */
        enum CreationAction implements PrivilegedAction<Initializable> {

            /**
             * The singleton instance.
             */
            INSTANCE;

            /**
             * {@inheritDoc}
             */
            @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "Exception should not be rethrown but trigger a fallback")
            public Initializable run() {
                try {
                    try {
                        Class<?> methodType = Class.forName("java.lang.invoke.MethodType"), methodHandle = Class.forName("java.lang.invoke.MethodHandle");
                        return new ForJava8CapableVm(Class.forName("java.lang.invoke.MethodHandles$Lookup")
                                .getMethod("findVirtual", Class.class, String.class, methodType)
                                .invoke(ByteArrayClassLoader.methodHandle(), ClassLoader.class, "getClassLoadingLock", methodType.getMethod("methodType",
                                        Class.class,
                                        Class[].class).invoke(null, Object.class, new Class<?>[]{String.class})),
                                methodHandle.getMethod("bindTo", Object.class),
                                methodHandle.getMethod("invokeWithArguments", Object[].class));
                    } catch (Exception ignored) {
                        // On the bootstrap class loader, a lookup instance cannot be located reflectively. To avoid issuing a warning for accessing
                        // a protected method from outside of a class that is caused if the module system does not offer accessing the method.
                        return ClassFileVersion.ofThisVm().isAtLeast(ClassFileVersion.JAVA_V9) && ByteArrayClassLoader.class.getClassLoader() == null
                                ? SynchronizationStrategy.ForLegacyVm.INSTANCE
                                : new ForJava7CapableVm(ClassLoader.class.getDeclaredMethod("getClassLoadingLock", String.class));
                    }
                } catch (Exception ignored) {
                    return SynchronizationStrategy.ForLegacyVm.INSTANCE;
                }
            }
        }

        /**
         * A synchronization engine for a VM that is not aware of parallel-capable class loaders.
         */
        enum ForLegacyVm implements SynchronizationStrategy, Initializable {

            /**
             * The singleton instance.
             */
            INSTANCE;

            /**
             * {@inheritDoc}
             */
            public Object getClassLoadingLock(ByteArrayClassLoader classLoader, String name) {
                return classLoader;
            }

            /**
             * {@inheritDoc}
             */
            public SynchronizationStrategy initialize() {
                return this;
            }
        }

        /**
         * A synchronization engine for a VM that is aware of parallel-capable class loaders.
         */
        @HashCodeAndEqualsPlugin.Enhance
        class ForJava7CapableVm implements SynchronizationStrategy, Initializable {

            /**
             * The {@code ClassLoader#getClassLoadingLock(String)} method.
             */
            private final Method method;

            /**
             * Creates a new synchronization strategy.
             *
             * @param method The {@code ClassLoader#getClassLoadingLock(String)} method.
             */
            protected ForJava7CapableVm(Method method) {
                this.method = method;
            }

            /**
             * {@inheritDoc}
             */
            public Object getClassLoadingLock(ByteArrayClassLoader classLoader, String name) {
                try {
                    return method.invoke(classLoader, name);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Cannot access class loading lock for " + name + " on " + classLoader, exception);
                } catch (InvocationTargetException exception) {
                    throw new IllegalStateException("Error when getting " + name + " on " + classLoader, exception);
                }
            }

            /**
             * {@inheritDoc}
             */
            @SuppressFBWarnings(value = "DP_DO_INSIDE_DO_PRIVILEGED", justification = "Privilege is explicitly user responsibility")
            public SynchronizationStrategy initialize() {
                try {
                    method.setAccessible(true);
                    return this;
                } catch (Exception ignored) {
                    return ForLegacyVm.INSTANCE;
                }
            }
        }

        /**
         * A synchronization engine for a VM that is aware of parallel-capable class loaders using method handles to respect module boundaries.
         */
        @HashCodeAndEqualsPlugin.Enhance
        class ForJava8CapableVm implements SynchronizationStrategy, Initializable {

            /**
             * The {@code java.lang.invoke.MethodHandle} to use.
             */
            private final Object methodHandle;

            /**
             * The {@code java.lang.invoke.MethodHandle#bindTo(Object)} method.
             */
            private final Method bindTo;

            /**
             * The {@code java.lang.invoke.MethodHandle#invokeWithArguments(Object[])} method.
             */
            private final Method invokeWithArguments;

            /**
             * Creates a new synchronization strategy.
             *
             * @param methodHandle        The {@code java.lang.invoke.MethodHandle} to use.
             * @param bindTo              The {@code java.lang.invoke.MethodHandle#bindTo(Object)} method.
             * @param invokeWithArguments The {@code java.lang.invoke.MethodHandle#invokeWithArguments(Object[])} method.
             */
            protected ForJava8CapableVm(Object methodHandle, Method bindTo, Method invokeWithArguments) {
                this.methodHandle = methodHandle;
                this.bindTo = bindTo;
                this.invokeWithArguments = invokeWithArguments;
            }

            /**
             * {@inheritDoc}
             */
            public SynchronizationStrategy initialize() {
                return this;
            }

            /**
             * {@inheritDoc}
             */
            public Object getClassLoadingLock(ByteArrayClassLoader classLoader, String name) {
                try {
                    return invokeWithArguments.invoke(bindTo.invoke(methodHandle, classLoader), (Object) new Object[]{name});
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Cannot access class loading lock for " + name + " on " + classLoader, exception);
                } catch (InvocationTargetException exception) {
                    throw new IllegalStateException("Error when getting " + name + " on " + classLoader, exception);
                }
            }
        }
    }

    /**
     * An action for defining a located class that is not yet loaded.
     */
    @HashCodeAndEqualsPlugin.Enhance(includeSyntheticFields = true)
    protected class ClassDefinitionAction implements PrivilegedAction<Class<?>> {

        /**
         * The binary name of the class to define.
         */
        private final String name;

        /**
         * The binary representation of the class to be loaded.
         */
        private final byte[] binaryRepresentation;

        /**
         * Creates a new class definition action.
         *
         * @param name                 The binary name of the class to define.
         * @param binaryRepresentation The binary representation of the class to be loaded.
         */
        protected ClassDefinitionAction(String name, byte[] binaryRepresentation) {
            this.name = name;
            this.binaryRepresentation = binaryRepresentation;
        }

        /**
         * {@inheritDoc}
         */
        public Class<?> run() {
            int packageIndex = name.lastIndexOf('.');
            if (packageIndex != -1) {
                String packageName = name.substring(0, packageIndex);
                PackageDefinitionStrategy.Definition definition = packageDefinitionStrategy.define(ByteArrayClassLoader.this, packageName, name);
                if (definition.isDefined()) {
                    Package definedPackage = PACKAGE_LOOKUP_STRATEGY.apply(ByteArrayClassLoader.this, packageName);
                    if (definedPackage == null) {
                        definePackage(packageName,
                                definition.getSpecificationTitle(),
                                definition.getSpecificationVersion(),
                                definition.getSpecificationVendor(),
                                definition.getImplementationTitle(),
                                definition.getImplementationVersion(),
                                definition.getImplementationVendor(),
                                definition.getSealBase());
                    } else if (!definition.isCompatibleTo(definedPackage)) {
                        throw new SecurityException("Sealing violation for package " + packageName);
                    }
                }
            }
            return defineClass(name, binaryRepresentation, FROM_BEGINNING, binaryRepresentation.length, protectionDomain);
        }
    }

    /**
     * A package lookup strategy for locating a package by name.
     */
    protected interface PackageLookupStrategy {

        /**
         * Returns a package for a given byte array class loader and a name.
         *
         * @param classLoader The class loader to locate a package for.
         * @param name        The name of the package.
         * @return A suitable package or {@code null} if no such package exists.
         */
        Package apply(ByteArrayClassLoader classLoader, String name);

        /**
         * A creation action for a package lookup strategy.
         */
        enum CreationAction implements PrivilegedAction<PackageLookupStrategy> {

            /**
             * The singleton instance.
             */
            INSTANCE;

            /**
             * {@inheritDoc}
             */
            @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "Exception should not be rethrown but trigger a fallback")
            public PackageLookupStrategy run() {
                if (JavaModule.isSupported()) { // Avoid accidental lookup of method with same name in Java 8 J9 VM.
                    try {
                        return new PackageLookupStrategy.ForJava9CapableVm(ClassLoader.class.getMethod("getDefinedPackage", String.class));
                    } catch (Exception ignored) {
                        return PackageLookupStrategy.ForLegacyVm.INSTANCE;
                    }
                } else {
                    return PackageLookupStrategy.ForLegacyVm.INSTANCE;
                }
            }
        }

        /**
         * A package lookup strategy for a VM prior to Java 9.
         */
        enum ForLegacyVm implements PackageLookupStrategy {

            /**
             * The singleton instance.
             */
            INSTANCE;

            /**
             * {@inheritDoc}
             */
            public Package apply(ByteArrayClassLoader classLoader, String name) {
                return classLoader.doGetPackage(name);
            }
        }

        /**
         * A package lookup strategy for Java 9 or newer.
         */
        @HashCodeAndEqualsPlugin.Enhance
        class ForJava9CapableVm implements PackageLookupStrategy {

            /**
             * The {@code java.lang.ClassLoader#getDefinedPackage(String)} method.
             */
            private final Method getDefinedPackage;

            /**
             * Creates a new package lookup strategy for a modern VM.
             *
             * @param getDefinedPackage The {@code java.lang.ClassLoader#getDefinedPackage(String)} method.
             */
            protected ForJava9CapableVm(Method getDefinedPackage) {
                this.getDefinedPackage = getDefinedPackage;
            }

            /**
             * {@inheritDoc}
             */
            public Package apply(ByteArrayClassLoader classLoader, String name) {
                try {
                    return (Package) getDefinedPackage.invoke(classLoader, name);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Cannot access " + getDefinedPackage, exception);
                } catch (InvocationTargetException exception) {
                    throw new IllegalStateException("Cannot invoke " + getDefinedPackage, exception.getCause());
                }
            }
        }
    }

    /**
     * A persistence handler decides on whether the byte array that represents a loaded class is exposed by
     * the {@link java.lang.ClassLoader#getResourceAsStream(String)} method.
     */
    public enum PersistenceHandler {

        /**
         * The manifest persistence handler retains all class file representations and makes them accessible.
         */
        MANIFEST(true) {
            @Override
            protected byte[] lookup(String name, ConcurrentMap<String, byte[]> typeDefinitions) {
                return typeDefinitions.get(name);
            }

            @Override
            protected URL url(String resourceName, ConcurrentMap<String, byte[]> typeDefinitions) {
                if (!resourceName.endsWith(CLASS_FILE_SUFFIX)) {
                    return NO_URL;
                } else if (resourceName.startsWith("/")) {
                    resourceName = resourceName.substring(1);
                }
                String typeName = resourceName.replace('/', '.').substring(FROM_BEGINNING, resourceName.length() - CLASS_FILE_SUFFIX.length());
                byte[] binaryRepresentation = typeDefinitions.get(typeName);
                return binaryRepresentation == null
                        ? NO_URL
                        : AccessController.doPrivileged(new UrlDefinitionAction(resourceName, binaryRepresentation));
            }

            @Override
            protected void release(String name, ConcurrentMap<String, byte[]> typeDefinitions) {
                /* do nothing */
            }
        },

        /**
         * The latent persistence handler hides all class file representations and does not make them accessible
         * even before they are loaded.
         */
        LATENT(false) {
            @Override
            protected byte[] lookup(String name, ConcurrentMap<String, byte[]> typeDefinitions) {
                return typeDefinitions.remove(name);
            }

            @Override
            protected URL url(String resourceName, ConcurrentMap<String, byte[]> typeDefinitions) {
                return NO_URL;
            }

            @Override
            protected void release(String name, ConcurrentMap<String, byte[]> typeDefinitions) {
                typeDefinitions.remove(name);
            }
        };

        /**
         * The suffix of files in the Java class file format.
         */
        private static final String CLASS_FILE_SUFFIX = ".class";

        /**
         * {@code true} if this persistence handler represents manifest class file storage.
         */
        private final boolean manifest;

        /**
         * Creates a new persistence handler.
         *
         * @param manifest {@code true} if this persistence handler represents manifest class file storage.
         */
        PersistenceHandler(boolean manifest) {
            this.manifest = manifest;
        }

        /**
         * Checks if this persistence handler represents manifest class file storage.
         *
         * @return {@code true} if this persistence handler represents manifest class file storage.
         */
        public boolean isManifest() {
            return manifest;
        }

        /**
         * Performs a lookup of a class file by its name.
         *
         * @param name            The name of the class to be loaded.
         * @param typeDefinitions A map of fully qualified class names pointing to their binary representations.
         * @return The byte array representing the requested class or {@code null} if no such class is known.
         */
        protected abstract byte[] lookup(String name, ConcurrentMap<String, byte[]> typeDefinitions);

        /**
         * Returns a URL representing a class file.
         *
         * @param resourceName    The name of the requested resource.
         * @param typeDefinitions A mapping of byte arrays by their type names.
         * @return A URL representing the type definition or {@code null} if the requested resource does not represent a class file.
         */
        protected abstract URL url(String resourceName, ConcurrentMap<String, byte[]> typeDefinitions);

        /**
         * Removes the binary representation of the supplied type if this class loader is latent.
         *
         * @param name            The name of the type.
         * @param typeDefinitions A mapping of byte arrays by their type names.
         */
        protected abstract void release(String name, ConcurrentMap<String, byte[]> typeDefinitions);

        /**
         * An action to define a URL that represents a class file.
         */
        @HashCodeAndEqualsPlugin.Enhance
        protected static class UrlDefinitionAction implements PrivilegedAction<URL> {

            /**
             * The URL's encoding character set.
             */
            private static final String ENCODING = "UTF-8";

            /**
             * A value to define a standard port as Byte Buddy's URLs do not represent a port.
             */
            private static final int NO_PORT = -1;

            /**
             * Indicates that Byte Buddy's URLs do not have a file segment.
             */
            private static final String NO_FILE = "";

            /**
             * The name of the type that this URL represents.
             */
            private final String typeName;

            /**
             * The binary representation of the type's class file.
             */
            private final byte[] binaryRepresentation;

            /**
             * Creates a new URL definition action.
             *
             * @param typeName             The name of the type that this URL represents.
             * @param binaryRepresentation The binary representation of the type's class file.
             */
            protected UrlDefinitionAction(String typeName, byte[] binaryRepresentation) {
                this.typeName = typeName;
                this.binaryRepresentation = binaryRepresentation;
            }

            /**
             * {@inheritDoc}
             */
            public URL run() {
                try {
                    return new URL(URL_SCHEMA,
                            URLEncoder.encode(typeName.replace('.', '/'), ENCODING),
                            NO_PORT,
                            NO_FILE,
                            new ByteArrayUrlStreamHandler(binaryRepresentation));
                } catch (MalformedURLException exception) {
                    throw new IllegalStateException("Cannot create URL for " + typeName, exception);
                } catch (UnsupportedEncodingException exception) {
                    throw new IllegalStateException("Could not find encoding: " + ENCODING, exception);
                }
            }

            /**
             * A stream handler that returns the given binary representation.
             */
            @HashCodeAndEqualsPlugin.Enhance
            protected static class ByteArrayUrlStreamHandler extends URLStreamHandler {

                /**
                 * The binary representation of a type's class file.
                 */
                private final byte[] binaryRepresentation;

                /**
                 * Creates a new byte array URL stream handler.
                 *
                 * @param binaryRepresentation The binary representation of a type's class file.
                 */
                protected ByteArrayUrlStreamHandler(byte[] binaryRepresentation) {
                    this.binaryRepresentation = binaryRepresentation;
                }

                /**
                 * {@inheritDoc}
                 */
                protected URLConnection openConnection(URL url) {
                    return new ByteArrayUrlConnection(url, new ByteArrayInputStream(binaryRepresentation));
                }

                /**
                 * A URL connection for a given byte array.
                 */
                protected static class ByteArrayUrlConnection extends URLConnection {

                    /**
                     * The input stream to return for this connection.
                     */
                    private final InputStream inputStream;

                    /**
                     * Creates a new byte array URL connection.
                     *
                     * @param url         The URL that this connection represents.
                     * @param inputStream The input stream to return from this connection.
                     */
                    protected ByteArrayUrlConnection(URL url, InputStream inputStream) {
                        super(url);
                        this.inputStream = inputStream;
                    }

                    /**
                     * {@inheritDoc}
                     */
                    public void connect() {
                        connected = true;
                    }

                    /**
                     * {@inheritDoc}
                     */
                    public InputStream getInputStream() {
                        connect(); // Mimics the semantics of an actual URL connection.
                        return inputStream;
                    }
                }
            }
        }
    }

    /**
     * <p>
     * A {@link net.bytebuddy.dynamic.loading.ByteArrayClassLoader} which applies child-first semantics for the
     * given type definitions.
     * </p>
     * <p>
     * <b>Important</b>: Package definitions remain their parent-first semantics as loaded package definitions do not expose their class loaders.
     * Also, it is not possible to make this class or its subclass parallel-capable as the loading strategy is overridden.
     * </p>
     */
    public static class ChildFirst extends ByteArrayClassLoader {

        /**
         * The suffix of files in the Java class file format.
         */
        private static final String CLASS_FILE_SUFFIX = ".class";

        /*
         * Register class loader as parallel capable if the current VM supports it.
         */
        static {
            doRegisterAsParallelCapable();
        }

        /**
         * Registers class loader as parallel capable if possible.
         */
        @SuppressFBWarnings(value = "DP_DO_INSIDE_DO_PRIVILEGED", justification = "Must be invoked from targeting ClassLoader class.")
        private static void doRegisterAsParallelCapable() {
            try {
                Method method = ClassLoader.class.getDeclaredMethod("registerAsParallelCapable");
                method.setAccessible(true);
                method.invoke(null);
            } catch (Throwable ignored) {
                /* do nothing */
            }
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent          The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param typeDefinitions A map of fully qualified class names pointing to their binary representations.
         */
        public ChildFirst(ClassLoader parent, Map<String, byte[]> typeDefinitions) {
            super(parent, typeDefinitions);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent          The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param sealed          {@code true} if this class loader is sealed.
         * @param typeDefinitions A map of fully qualified class names pointing to their binary representations.
         */
        public ChildFirst(ClassLoader parent, boolean sealed, Map<String, byte[]> typeDefinitions) {
            super(parent, sealed, typeDefinitions);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent             The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param typeDefinitions    A map of fully qualified class names pointing to their binary representations.
         * @param persistenceHandler The persistence handler of this class loader.
         */
        public ChildFirst(ClassLoader parent, Map<String, byte[]> typeDefinitions, PersistenceHandler persistenceHandler) {
            super(parent, typeDefinitions, persistenceHandler);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent             The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param sealed             {@code true} if this class loader is sealed.
         * @param typeDefinitions    A map of fully qualified class names pointing to their binary representations.
         * @param persistenceHandler The persistence handler of this class loader.
         */
        public ChildFirst(ClassLoader parent, boolean sealed, Map<String, byte[]> typeDefinitions, PersistenceHandler persistenceHandler) {
            super(parent, sealed, typeDefinitions, persistenceHandler);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
         * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
         * @param persistenceHandler        The persistence handler of this class loader.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         */
        public ChildFirst(ClassLoader parent,
                          Map<String, byte[]> typeDefinitions,
                          ProtectionDomain protectionDomain,
                          PersistenceHandler persistenceHandler,
                          PackageDefinitionStrategy packageDefinitionStrategy) {
            super(parent, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param sealed                    {@code true} if this class loader is sealed.
         * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
         * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
         * @param persistenceHandler        The persistence handler of this class loader.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         */
        public ChildFirst(ClassLoader parent,
                          boolean sealed,
                          Map<String, byte[]> typeDefinitions,
                          ProtectionDomain protectionDomain,
                          PersistenceHandler persistenceHandler,
                          PackageDefinitionStrategy packageDefinitionStrategy) {
            super(parent, sealed, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
         * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
         * @param persistenceHandler        The persistence handler of this class loader.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         * @param classFilePostProcessor    A post processor for class files to apply p
         */
        public ChildFirst(ClassLoader parent,
                          Map<String, byte[]> typeDefinitions,
                          ProtectionDomain protectionDomain,
                          PersistenceHandler persistenceHandler,
                          PackageDefinitionStrategy packageDefinitionStrategy,
                          ClassFilePostProcessor classFilePostProcessor) {
            super(parent, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy, classFilePostProcessor);
        }

        /**
         * Creates a new child-first byte array class loader.
         *
         * @param parent                    The {@link java.lang.ClassLoader} that is the parent of this class loader.
         * @param sealed                    {@code true} if this class loader is sealed.
         * @param typeDefinitions           A map of fully qualified class names pointing to their binary representations.
         * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
         * @param persistenceHandler        The persistence handler of this class loader.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         * @param classFilePostProcessor    A post processor for class files to apply p
         */
        public ChildFirst(ClassLoader parent,
                          boolean sealed,
                          Map<String, byte[]> typeDefinitions,
                          ProtectionDomain protectionDomain,
                          PersistenceHandler persistenceHandler,
                          PackageDefinitionStrategy packageDefinitionStrategy,
                          ClassFilePostProcessor classFilePostProcessor) {
            super(parent, sealed, typeDefinitions, protectionDomain, persistenceHandler, packageDefinitionStrategy, classFilePostProcessor);
        }

        /**
         * Loads a given set of class descriptions and their binary representations using a child-first class loader.
         *
         * @param classLoader The parent class loader.
         * @param types       The unloaded types to be loaded.
         * @return A map of the given type descriptions pointing to their loaded representations.
         */
        public static Map<TypeDescription, Class<?>> load(ClassLoader classLoader, Map<TypeDescription, byte[]> types) {
            return load(classLoader,
                    types,
                    ClassLoadingStrategy.NO_PROTECTION_DOMAIN,
                    PersistenceHandler.LATENT,
                    PackageDefinitionStrategy.Trivial.INSTANCE,
                    false,
                    true);
        }

        /**
         * Loads a given set of class descriptions and their binary representations using a child-first class loader.
         *
         * @param classLoader               The parent class loader.
         * @param types                     The unloaded types to be loaded.
         * @param protectionDomain          The protection domain to apply where {@code null} references an implicit protection domain.
         * @param persistenceHandler        The persistence handler of the created class loader.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         * @param forbidExisting            {@code true} if the class loading should throw an exception if a class was already loaded by a parent class loader.
         * @param sealed                    {@code true} if the class loader should be sealed.
         * @return A map of the given type descriptions pointing to their loaded representations.
         */
        @SuppressFBWarnings(value = "DP_CREATE_CLASSLOADER_INSIDE_DO_PRIVILEGED", justification = "Privilege is explicit user responsibility")
        public static Map<TypeDescription, Class<?>> load(ClassLoader classLoader,
                                                          Map<TypeDescription, byte[]> types,
                                                          ProtectionDomain protectionDomain,
                                                          PersistenceHandler persistenceHandler,
                                                          PackageDefinitionStrategy packageDefinitionStrategy,
                                                          boolean forbidExisting,
                                                          boolean sealed) {
            Map<String, byte[]> typesByName = new HashMap<String, byte[]>();
            for (Map.Entry<TypeDescription, byte[]> entry : types.entrySet()) {
                typesByName.put(entry.getKey().getName(), entry.getValue());
            }
            classLoader = new ChildFirst(classLoader,
                    sealed,
                    typesByName,
                    protectionDomain,
                    persistenceHandler,
                    packageDefinitionStrategy,
                    ClassFilePostProcessor.NoOp.INSTANCE);
            Map<TypeDescription, Class<?>> result = new LinkedHashMap<TypeDescription, Class<?>>();
            for (TypeDescription typeDescription : types.keySet()) {
                try {
                    Class<?> type = Class.forName(typeDescription.getName(), false, classLoader);
                    if (forbidExisting && type.getClassLoader() != classLoader) {
                        throw new IllegalStateException("Class already loaded: " + type);
                    }
                    result.put(typeDescription, type);
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException("Cannot load class " + typeDescription, exception);
                }
            }
            return result;
        }

        /**
         * {@inheritDoc}
         */
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (SYNCHRONIZATION_STRATEGY.initialize().getClassLoadingLock(this, name)) {
                Class<?> type = findLoadedClass(name);
                if (type != null) {
                    return type;
                }
                try {
                    type = findClass(name);
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                } catch (ClassNotFoundException exception) {
                    // If an unknown class is loaded, this implementation causes the findClass method of this instance
                    // to be triggered twice. This is however of minor importance because this would result in a
                    // ClassNotFoundException what does not alter the outcome.
                    return super.loadClass(name, resolve);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        public URL getResource(String name) {
            URL url = persistenceHandler.url(name, typeDefinitions);
            // If a class resource is defined by this class loader but it is not defined in a manifest manner,
            // the resource of the parent class loader should be shadowed by 'null'. Note that the delegation
            // model causes a redundant query to the persistent handler but renders a correct result.
            return url != null || isShadowed(name)
                    ? url
                    : super.getResource(name);
        }

        /**
         * {@inheritDoc}
         */
        public Enumeration<URL> getResources(String name) throws IOException {
            URL url = persistenceHandler.url(name, typeDefinitions);
            return url == null
                    ? super.getResources(name)
                    : new PrependingEnumeration(url, super.getResources(name));
        }

        /**
         * Checks if a resource name represents a class file of a class that was loaded by this class loader.
         *
         * @param resourceName The resource name of the class to be exposed as its class file.
         * @return {@code true} if this class represents a class that is being loaded by this class loader.
         */
        private boolean isShadowed(String resourceName) {
            if (persistenceHandler.isManifest() || !resourceName.endsWith(CLASS_FILE_SUFFIX)) {
                return false;
            }
            // This synchronization is required to avoid a racing condition to the actual class loading.
            synchronized (this) {
                String typeName = resourceName.replace('/', '.').substring(0, resourceName.length() - CLASS_FILE_SUFFIX.length());
                if (typeDefinitions.containsKey(typeName)) {
                    return true;
                }
                Class<?> loadedClass = findLoadedClass(typeName);
                return loadedClass != null && loadedClass.getClassLoader() == this;
            }
        }

        /**
         * An enumeration that prepends an element to another enumeration and skips the last element of the provided enumeration.
         */
        protected static class PrependingEnumeration implements Enumeration<URL> {

            /**
             * The next element to return from this enumeration or {@code null} if such an element does not exist.
             */
            private URL nextElement;

            /**
             * The enumeration from which the next elements should be pulled.
             */
            private final Enumeration<URL> enumeration;

            /**
             * Creates a new prepending enumeration.
             *
             * @param url         The first element of the enumeration.
             * @param enumeration An enumeration that is used for pulling subsequent urls.
             */
            protected PrependingEnumeration(URL url, Enumeration<URL> enumeration) {
                nextElement = url;
                this.enumeration = enumeration;
            }

            /**
             * {@inheritDoc}
             */
            public boolean hasMoreElements() {
                return nextElement != null && enumeration.hasMoreElements();
            }

            /**
             * {@inheritDoc}
             */
            public URL nextElement() {
                if (nextElement != null && enumeration.hasMoreElements()) {
                    try {
                        return nextElement;
                    } finally {
                        nextElement = enumeration.nextElement();
                    }
                } else {
                    throw new NoSuchElementException();
                }
            }
        }
    }

    /**
     * An enumeration without any elements.
     */
    protected enum EmptyEnumeration implements Enumeration<URL> {

        /**
         * The singleton instance.
         */
        INSTANCE;

        /**
         * {@inheritDoc}
         */
        public boolean hasMoreElements() {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        public URL nextElement() {
            throw new NoSuchElementException();
        }
    }

    /**
     * An enumeration that contains a single element.
     */
    protected static class SingletonEnumeration implements Enumeration<URL> {

        /**
         * The current element or {@code null} if this enumeration does not contain further elements.
         */
        private URL element;

        /**
         * Creates a new singleton enumeration.
         *
         * @param element The only element.
         */
        protected SingletonEnumeration(URL element) {
            this.element = element;
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasMoreElements() {
            return element != null;
        }

        /**
         * {@inheritDoc}
         */
        public URL nextElement() {
            if (element == null) {
                throw new NoSuchElementException();
            } else {
                try {
                    return element;
                } finally {
                    element = null;
                }
            }
        }
    }
}
