package com.seibel.distanthorizons

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import groovy.transform.Canonical

import java.util.jar.JarFile

enum LWJGLVersion {
    TWO(2),
    THREE(3)

    final int version

    LWJGLVersion(int version) {
        this.version = version
    }
}

abstract class GenerateLWJGLAbstraction extends DefaultTask {

    static final String LWJGL_ABSTRACTION_PACKAGE = "com.seibel.distanthorizons.lwjgl"

    @InputFiles
    @Classpath
    abstract ConfigurableFileCollection getLwjgl2Classpath()

    @InputFiles
    @Classpath
    abstract ConfigurableFileCollection getLwjgl3Classpath()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Canonical
    static class GLMethod {
        String returnType
        String name
        List<Tuple2<String, String>> params

        String signature() {
            return "${name}(${params.collect { it.first }.join(',')})${returnType}"
        }
    }

    @Canonical
    static class GLConstant {
        String name
        String type
        Object value
    }

    static class GLClassData {
        Set<GLMethod> methods = new LinkedHashSet<>()
        Set<GLConstant> constants = new LinkedHashSet<>()
    }

    @TaskAction
    void generate() {
        File outputDir = outputDirectory.get().asFile
        if (outputDir.exists()) {
            outputDir.deleteDir()
        }
        outputDir.mkdirs()

        // GL version classes to process
        Map<String, String> glVersions = [
            "11": "org/lwjgl/opengl/GL11",
            "12": "org/lwjgl/opengl/GL12",
            "13": "org/lwjgl/opengl/GL13",
            "14": "org/lwjgl/opengl/GL14",
            "15": "org/lwjgl/opengl/GL15",
            "20": "org/lwjgl/opengl/GL20",
            "21": "org/lwjgl/opengl/GL21",
            "30": "org/lwjgl/opengl/GL30",
            "31": "org/lwjgl/opengl/GL31",
            "32": "org/lwjgl/opengl/GL32",
            "33": "org/lwjgl/opengl/GL33",
            "40": "org/lwjgl/opengl/GL40",
            "41": "org/lwjgl/opengl/GL41",
            "42": "org/lwjgl/opengl/GL42",
            "43": "org/lwjgl/opengl/GL43",
            "44": "org/lwjgl/opengl/GL44",
            "45": "org/lwjgl/opengl/GL45",
            "46": "org/lwjgl/opengl/GL46"
        ]

        Map<String, GLClassData> allMethods = [:]

        glVersions.each { version, classPath ->
            GLClassData methods = extractGLMethods(classPath, lwjgl3Classpath.files)
            allMethods[version] = methods
            logger.info("Extracted ${methods.methods.size()} methods from GL${version}")
        }

        filterValidGLMethods(allMethods, glVersions, lwjgl2Classpath.files)

        // Generate LWJGLService interface
        //generateLWJGLServiceInterface(outputDir, allMethods)

        // Generate LWJGLService implementations
        //for (version in LWJGLVersion.values()) {
        //    generateLWJGLBackingService(outputDir, version, allMethods, glVersions)
        //}

        // Generate GLXY wrapper classes
        glVersions.keySet().each { version ->
            GLClassData methods = allMethods[version] ?: new GLClassData()
            generateGLWrapper(outputDir, version, methods)
        }

        logger.lifecycle("Generated LWJGL abstraction layer in ${outputDir.absolutePath}")
    }

    protected boolean isGLMethod(MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0
        boolean isPublic = (method.access & Opcodes.ACC_PUBLIC) != 0

        return isStatic && isPublic && method.name.startsWith("gl")
    }

    protected GLClassData extractGLMethods(String classPath, Set<File> classpathFiles) {
        GLClassData data = new GLClassData()

        for (File file in classpathFiles) {
            if (!file.exists() || !file.name.endsWith(".jar")) continue

            try {
                try (JarFile jar = new JarFile(file)) {
                    def entry = jar.getJarEntry("${classPath}.class")
                    if (entry == null) continue

                    try (InputStream stream = jar.getInputStream(entry)) {
                        ClassReader classReader = new ClassReader(stream)
                        ClassNode classNode = new ClassNode()
                        classReader.accept(classNode, 0)

                        classNode.fields.each { field ->
                            boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0
                            boolean isFinal = (field.access & Opcodes.ACC_FINAL) != 0
                            boolean isPublic = (field.access & Opcodes.ACC_PUBLIC) != 0

                            if (isStatic && isFinal && isPublic && field.value != null) {
                                Type type = Type.getType(field.desc)
                                String javaType = asmTypeToJavaType(type)

                                data.constants.add(new GLConstant(
                                    name: field.name,
                                    type: javaType,
                                    value: field.value
                                ))
                            }
                        }

                        classNode.methods.findAll { isGLMethod(it) }.each { method ->
                            Type methodType = Type.getMethodType(method.desc)
                            List<String> paramTypes = methodType.argumentTypes.collect { asmTypeToJavaType(it) }

                            if (paramTypes.any { it.startsWith("org.lwjgl.") }) {
                                // Do not emit overloads that require LWJGL class references
                                return
                            }

                            String returnType = asmTypeToJavaType(methodType.returnType)

                            // Try to get parameter names from bytecode
                            List<String> paramNames = []

                            // First try MethodParameters attribute (Java 8+ with -parameters flag)
                            if (method.parameters != null && !method.parameters.isEmpty()) {
                                method.parameters.each { param ->
                                    paramNames.add(param.name)
                                }
                            }
                            // Fall back to LocalVariableTable if available
                            else if (method.localVariables != null && !method.localVariables.isEmpty()) {
                                // For static methods, local variables start at index 0
                                // Sort by index and skip synthetic/internal variables
                                method.localVariables
                                    .findAll { it.index < paramTypes.size() }
                                    .sort { it.index }
                                    .each { localVar ->
                                        if (paramNames.size() < paramTypes.size()) {
                                            paramNames.add(localVar.name)
                                        }
                                    }
                            }

                            // Ensure we have enough parameter names
                            while (paramNames.size() < paramTypes.size()) {
                                paramNames.add("param${paramNames.size()}")
                            }

                            List<Tuple2<String, String>> params = paramTypes.indices.collect { idx ->
                                new Tuple2<>(paramTypes[idx], paramNames[idx])
                            }

                            data.methods.add(new GLMethod(
                                returnType: returnType,
                                name: method.name,
                                params: params
                            ))
                        }
                    }

                    if (!data.methods.isEmpty()) break
                }
            } catch (Exception e) {
                logger.warn("Could not read class ${classPath} from ${file.name}: ${e.message}")
            }
        }

        return data
    }

    protected void filterValidGLMethods(Map<String, GLClassData> allMethods, Map<String, String> glVersions, Set<File> classpathFiles) {
        Set<String> lwjgl2Signatures = new LinkedHashSet<>()

        for (File file in classpathFiles) {
            if (!file.exists() || !file.name.endsWith(".jar")) continue

            try (JarFile jar = new JarFile(file)) {
                allMethods.each { version, unusedData ->
                    String classBinaryName = glVersions[version]
                    def entry = jar.getJarEntry("${classBinaryName}.class")
                    if (entry == null) return

                    try (InputStream stream = jar.getInputStream(entry)) {
                        ClassReader classReader = new ClassReader(stream)
                        ClassNode classNode = new ClassNode()
                        classReader.accept(classNode, 0)

                        classNode.methods.findAll { isGLMethod(it) }.each { method ->
                            Type methodType = Type.getMethodType(method.desc)
                            List<String> paramTypes = methodType.argumentTypes.collect { asmTypeToJavaType(it) }
                            List<String> paramNames = []
                            while (paramNames.size() < paramTypes.size()) {
                                paramNames.add("param${paramNames.size()}")
                            }
                            String returnType = asmTypeToJavaType(methodType.returnType)

                            List<Tuple2<String, String>> params = paramTypes.indices.collect { idx ->
                                new Tuple2<>(paramTypes[idx], paramNames[idx])
                            }

                            lwjgl2Signatures.add(new GLMethod(name: method.name, returnType: returnType, params: params).signature())
                        }
                    }
                }
            }
        }

        allMethods.each { version, methodSet ->
            int oldSize = methodSet.methods.size()
            methodSet.methods.removeIf { !lwjgl2Signatures.contains(it.signature()) }
            int newSize = methodSet.methods.size()
            if (newSize < oldSize) {
                logger.info("Removed {} methods from GL{} that don't exist in LWJGL2", oldSize - newSize, version)
            }
        }
    }

    protected String asmTypeToJavaType(Type type) {
        switch (type.sort) {
            case Type.VOID: return "void"
            case Type.BOOLEAN: return "boolean"
            case Type.CHAR: return "char"
            case Type.BYTE: return "byte"
            case Type.SHORT: return "short"
            case Type.INT: return "int"
            case Type.FLOAT: return "float"
            case Type.LONG: return "long"
            case Type.DOUBLE: return "double"
            case Type.ARRAY: return asmTypeToJavaType(type.elementType) + ("[]" * type.dimensions)
            case Type.OBJECT: return type.className
            default: return type.className
        }
    }

    protected File getOutputDir(File outputDir, String group) {
        return new File(
            outputDir,
            group + File.separatorChar + LWJGL_ABSTRACTION_PACKAGE.replace('.' as char, File.separatorChar)
        )
    }

    protected void generateLWJGLServiceInterface(File outputDir, Map<String, GLClassData> allMethods) {
        File packageDir = getOutputDir(outputDir, "main")
        packageDir.mkdirs()

        File serviceFile = new File(packageDir, "LWJGLService.java")
        List<GLMethod> allUniqueMethods = allMethods.values().collect { it.methods }.flatten().unique { it.signature() }

        String methodsText = allUniqueMethods.collect { method ->
            String params = method.params.collect { "${it.first} ${it.second}" }.join(", ")
            "    ${method.returnType} ${method.name}(${params});"
        }.join("\n\n")

        serviceFile.text = """package ${LWJGL_ABSTRACTION_PACKAGE};

public interface LWJGLService {

    LWJGLService INSTANCE = createInstance();

    static LWJGLService constructInstance(String className) {
        try {
            var clz = Class.forName(className);
            return (LWJGLService)clz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static LWJGLService createInstance() {
        try {
            Class.forName("org.lwjgl.opengl.GL11");
            return constructInstance("${LWJGL_ABSTRACTION_PACKAGE}.LWJGL2Service");
        } catch (ClassNotFoundException e) {
            return constructInstance("${LWJGL_ABSTRACTION_PACKAGE}.LWJGL3Service");
        }
    }

${methodsText}
}
"""
    }

    protected void generateLWJGLBackingService(File outputDir, LWJGLVersion version, Map<String, GLClassData> allMethods, Map<String, String> glVersions) {
        File packageDir = getOutputDir(outputDir, "lwjgl" + version.version)
        packageDir.mkdirs()

        File serviceFile = new File(packageDir, "LWJGL${version.version}Service.java")

        // Create a map of methods to their GL class
        Map<String, String> methodToClass = [:]
        glVersions.each { ver, unusedPath ->
            allMethods[ver]?.methods?.each { method ->
                if (!methodToClass.containsKey(method.signature())) {
                    methodToClass[method.signature()] = ver
                }
            }
        }

        List<GLMethod> allUniqueMethods = allMethods.values().collect { it.methods }.flatten().unique { it.signature() }

        String methodsText = allUniqueMethods.collect { method ->
            String params = method.params.collect { "${it.first} ${it.second}" }.join(", ")
            String paramNames = method.params.collect { it.second }.join(", ")
            String returnStmt = method.returnType == "void" ? "" : "return "
            String glVersion = methodToClass[method.signature()] ?: "11"
            """    @Override
    public ${method.returnType} ${method.name}(${params}) {
        ${returnStmt}org.lwjgl.opengl.GL${glVersion}.${method.name}(${paramNames});
    }"""
        }.join("\n\n")

        serviceFile.text = """package ${LWJGL_ABSTRACTION_PACKAGE};

class LWJGL${version.version}Service implements LWJGLService {

${methodsText}
}
"""
    }

    protected static final List<String> GL_VERSION_CHAIN = [
        "11", "12", "13", "14", "15", "20", "21", "30", "31", "32", "33", "40", "41", "42", "43", "44", "45"
    ]

    protected void generateGLWrapper(File outputDir, String version, GLClassData classData) {
        File packageDir = getOutputDir(outputDir, "main")
        packageDir.mkdirs()

        String className = "GL${version}"
        File wrapperFile = new File(packageDir, "${className}.java")

        // Determine previous version in the chain, or null if none
        String previousVersion = null
        for (int i = 0; i < GL_VERSION_CHAIN.size() - 1; i++) {
            if (GL_VERSION_CHAIN[i + 1] == version) {
                previousVersion = GL_VERSION_CHAIN[i]
                break
            }
        }

        String extendsClause = previousVersion != null ? " extends GL${previousVersion}" : ""

        String constantsText = classData.constants.collect { constant ->
            "    public static final ${constant.type} ${constant.name} = ${constant.value};"
        }.join("\n\n")

        wrapperFile.text = """package ${LWJGL_ABSTRACTION_PACKAGE};

public class ${className}${extendsClause} {

${constantsText}
}
"""
    }
}
