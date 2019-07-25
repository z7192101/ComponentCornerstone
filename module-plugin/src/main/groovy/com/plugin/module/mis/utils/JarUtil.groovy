package com.plugin.module.mis.utils

import com.plugin.module.mis.extension.CompileOptions
import com.plugin.module.mis.extension.Publication
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.internal.jvm.Jvm
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class JarUtil {


    /**
     * 打包Java源码为jar包依赖
     * @param project
     * @param publication
     * @param androidJarPath
     * @param compileOptions
     * @param vars
     * @return
     */
    static File packJavaSourceJar(Project project, Publication publication, String androidJarPath,
                                  CompileOptions compileOptions, boolean vars) {
        //.../build/mis 重置
        publication.buildDir.deleteDir()
        publication.buildDir.mkdirs()

        //.../build/mis/source
        //.../build/mis/classes
        //.../build/mis/output
        def sourceDir = new File(publication.buildDir, "source")
        def classesDir = new File(publication.buildDir, "classes")
        def outputDir = new File(publication.buildDir, "output")
        sourceDir.mkdirs()
        classesDir.mkdirs()
        outputDir.mkdirs()

        //复制 mis sourceSet 的java或kotlin文件到source目录中，返回source目录下的文件路径
        def argFiles = []
        File file = new File(publication.misSourceSet.path)
        String prefix = publication.misSourceSet.path
        filterJavaSource(file, prefix, sourceDir, argFiles, publication.sourceFilter)
        if (argFiles.size() == 0) {
            return null
        }

        //迁移发布依赖到project依赖，从{name}路径重点读取该依赖
        def name = "mis[${publication.groupId}-${publication.artifactId}]Classpath"
        if (publication.dependencies != null) {
            if (publication.dependencies.implementation != null) {
                publication.dependencies.implementation.each {
                    project.dependencies.add(name, it)
                }
            }
            if (publication.dependencies.compileOnly != null) {
                publication.dependencies.compileOnly.each {
                    project.dependencies.add(name, it)
                }
            }
        }

        Configuration configuration = project.configurations.create(name)
        def classPath = [androidJarPath]
        configuration.copy().files.each {
            if (it.name.endsWith('.aar')) {
                classPath << getAARClassesJar(it)
            } else {
                classPath << it.absolutePath
            }
        }
        project.configurations.remove(configuration)

        return generateJavaSourceJar(classesDir, argFiles, classPath, compileOptions, vars)
    }


    /**
     * 生成java jar包
     * @param classesDir
     * @param argFiles
     * @param classPath
     * @param compileOptions
     * @param vars
     * @return
     */
    private static File generateJavaSourceJar(File classesDir,
                                              List<String> argFiles,
                                              List<String> classPath,
                                              CompileOptions compileOptions,
                                              boolean vars) {

        //window classpath 路径分割符号与 mac/linux需要做区分
        def classpathSeparator = ";"
        if (!System.properties['os.name'].toLowerCase().contains('windows')) {
            classpathSeparator = ":"
        }

        boolean keepParameters = vars && Jvm.current().javaVersion >= JavaVersion.VERSION_1_8

        //读取java和kotlin文件
        List<String> javaFiles = new ArrayList<>()
        List<String> kotlinFiles = new ArrayList<>()
        argFiles.each {
            if (it.endsWith('.java')) {
                javaFiles.add(it)
            } else if (it.endsWith('.kt')) {
                kotlinFiles.add(it)
            }
        }


        if (!kotlinFiles.isEmpty()) {
            K2JVMCompiler compiler = new K2JVMCompiler()
            def args = new ArrayList<String>()
            args.addAll(argFiles)
            args.add('-d')
            args.add(classesDir.absolutePath)
            args.add('-no-stdlib')
            if (keepParameters) {
                args.add('-java-parameters')
            }

            JavaVersion targetCompatibility = compileOptions.targetCompatibility
            def target = targetCompatibility.toString()
            if (!targetCompatibility.isJava8() && !targetCompatibility.isJava6()) {
                throw new GradleException("Failure to compile mis kotlin source to bytecode: unknown JVM target version: $target, supported versions: 1.6, 1.8\nTry:\n " +
                        "   mis {\n" +
                        "       ...\n" +
                        "       compileOptions {\n" +
                        "           sourceCompatibility JavaVersion.VERSION_1_8\n" +
                        "           targetCompatibility JavaVersion.VERSION_1_8\n" +
                        "       }\n" +
                        "   }")
            }


            args.add('-jvm-target')
            args.add(target)

            if (classPath.size() > 0) {
                args.add('-classpath')
                args.add(classPath.join(classpathSeparator))
            }

            ExitCode exitCode = compiler.exec(System.out, (String[]) args.toArray())
            if (exitCode != ExitCode.OK) {
                throw new GradleException("Failure to compile mis kotlin source to bytecode.")
            }

            new File(classesDir, '/META-INF').deleteDir()

            classPath.add(classesDir.absolutePath)
        }

        if (!javaFiles.isEmpty()) {
            def command = "javac " + (keepParameters ? "-parameters" : "") + " -d . -encoding UTF-8 -target " + compileOptions.targetCompatibility.toString() + " -source " + compileOptions.sourceCompatibility.toString() + (classPath.size() > 0 ? (" -classpath " + classPath.join(classpathSeparator) + " ") : "") + javaFiles.join(' ')
            def p = (command).execute(null, classesDir)

            def result = p.waitFor(30, TimeUnit.SECONDS)
            if (!result) {
                throw new GradleException("Timed out when compile mis java source to bytecode with command.\nExecute command:\n" + command)
            }

            if (p.exitValue() != 0) {
                throw new GradleException("Failure to compile mis java source to bytecode: \n" + p.err.text + "\nExecute command:\n" + command)
            }
        }

        def p = "jar cvf outputs/classes.jar -C classes . ".execute(null, classesDir.parentFile)
        def result = p.waitFor()
        p.destroy()
        p = null
        if (result != 0) {
            throw new RuntimeException("failure to package classes.jar: \n" + p.err.text)
        }

        return new File(classesDir.parentFile, 'outputs/classes.jar')
    }

    /**
     * 打包java文档
     * @param publication
     * @return
     */
    static File packJavaDocSourceJar(Publication publication) {
        def javaSource = new File(publication.buildDir, "javaSource")
        javaSource.deleteDir()
        javaSource.mkdirs()
        filterJavaDocSource(new File(publication.misSourceSet.path), publication.misSourceSet.path, javaSource)
        return generateJavaDocSourceJar(javaSource)
    }

    private static File generateJavaDocSourceJar(File sourceDir) {
        def p = "jar cvf ../outputs/classes-source.jar .".execute(null, sourceDir)
        def result = p.waitFor()
        if (result != 0) {
            throw new RuntimeException("failure to make mis-sdk java source directory: \n" + p.err.text)
        }
        def sourceJar = new File(sourceDir.parentFile, 'outputs/classes-source.jar')
        return sourceJar
    }


    /**
     *
     * @param file
     * @param prefix
     * @param sourceDir
     * @param argFiles
     * @param sourceFilter
     */
    private static void filterJavaSource(File file, String prefix, File sourceDir,
                                         def argFiles, Closure sourceFilter) {
        //如果是目录，则递归过滤
        if (file.isDirectory()) {
            file.listFiles().each { File childFile ->
                filterJavaSource(childFile, prefix, sourceDir, argFiles, sourceFilter)
            }
        } else {
            //如果是java或者kt文件
            if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                def packageName = file.parent.replace(prefix, "")
                def targetParent = new File(sourceDir, packageName)
                if (!targetParent.exists()) targetParent.mkdirs()
                def target = new File(targetParent, file.name)
                MisUtil.copyFile(file, target)
                argFiles << target.absolutePath

                if (sourceFilter != null) {
                    sourceFilter.call(target)
                }
            }
        }
    }

    private static void filterJavaDocSource(File file, String prefix, File javaDocDir) {
        if (file.isDirectory()) {
            file.listFiles().each { File childFile ->
                filterJavaDocSource(childFile, prefix, javaDocDir)
            }
        } else {
            if (file.name.endsWith(".java") || file.name.endsWith('.kt')) {
                def packageName = file.parent.replace(prefix, "")
                def targetParent = new File(javaDocDir, packageName)
                if (!targetParent.exists()) targetParent.mkdirs()
                def target = new File(targetParent, file.name)
                MisUtil.copyFile(file, target)
            }
        }
    }

    static boolean compareMavenJar(Project project, Publication publication, String localPath) {
        String filePath = null
        String fileName = publication.artifactId + "-" + publication.version + ".jar"
        def name = "mis[${publication.groupId}-${publication.artifactId}]Classpath"
        Configuration configuration = project.configurations.create(name)
        project.dependencies.add(name, publication.groupId + ":" + publication.artifactId + ":" + publication.version)
        configuration.copy().files.each {
            if (it.name.endsWith(fileName)) {
                filePath = it.absolutePath
            }
        }
        project.configurations.remove(configuration)
        if (filePath == null) return false
        return compareJar(localPath, filePath)
    }

    /**
     * 获取目标文件中包含 classes.jar 的文件并写入jarFile
     * @param input
     * @return
     */
    private static String getAARClassesJar(File input) {
        def jarFile = new File(input.getParent(), 'classes.jar')
        if (jarFile.exists()) return jarFile
        def zip = new ZipFile(input)
        zip.entries().each {
            if (it.isDirectory()) return
            if (it.name == 'classes.jar') {
                def fos = new FileOutputStream(jarFile)
                fos.write(zip.getInputStream(it).bytes)
                fos.close()
            }
        }
        zip.close()
        return jarFile.absolutePath
    }
}