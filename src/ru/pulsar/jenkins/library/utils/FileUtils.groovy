package ru.pulsar.jenkins.library.utils

import hudson.FilePath
import jenkins.model.Jenkins
import ru.pulsar.jenkins.library.IStepExecutor
import ru.pulsar.jenkins.library.ioc.ContextRegistry

import java.nio.file.Path

class FileUtils {

    static FilePath getFilePath(String path) {

        IStepExecutor steps = ContextRegistry.getContext().getStepExecutor()

        def env = steps.env();

        String nodeName = env.NODE_NAME;
        if (nodeName == null) {
            steps.error 'Переменная среды NODE_NAME не задана. Запуск вне node или без agent?'
        }

        if (nodeName == "master" || nodeName == "built-in") {
            return new FilePath(new File(path));
        } else {
            return new FilePath(Jenkins.getInstanceOrNull().getComputer(nodeName).getChannel(), path);
        }
    }

    static String getLocalPath(FilePath filePath) {
        IStepExecutor steps = ContextRegistry.getContext().getStepExecutor()
        String workspacePath = steps.env().WORKSPACE
        String fileRemotePath = filePath.getRemote()

        // Нормализуем пути: заменяем обратные слэши на прямые
        workspacePath = workspacePath.replace('\\', '/')
        fileRemotePath = fileRemotePath.replace('\\', '/')

        // Проверяем, что файл находится внутри рабочей директории
        if (!fileRemotePath.startsWith(workspacePath)) {
            throw new IllegalArgumentException("File path is not within the workspace directory")
        }

        // Вычисляем относительный путь
        return fileRemotePath.substring(workspacePath.length() + 1)
    }

    static void loadFile(String filePathFrom, def env, String filePathTo) {

        FilePath localPathToFile = getFilePath(filePathTo)

        if (isValidUrl(filePathFrom)) {
            // If the path is a URL, download the file
            localPathToFile.copyFrom(new URL(filePathFrom))
        } else {
            // If the path is a local file, copy the file
            String localPath = getAbsolutePath(filePathFrom, env)
            FilePath localFilePath = getFilePath(localPath)
            
            // * Новый код с условием работы с большими файлами
            // ранее был только localPathToFile.copyFrom(localFilePath)
            // Определяем размер в Мб
            long sizeInBytes = localFilePath.length()
            BigDecimal sizeInMb = sizeInBytes / (1024.0 * 1024.0)

            Logger.println("Копирование файла размером ${sizeInMb} Мб функцией copyFrom из ${filePathFrom} в ${filePathTo}")
            if (sizeInMb > 3000) {
                copyWithSystemTools(localFilePath, localPathToFile)
            } else {
                localPathToFile.copyFrom(localFilePath)
            }          
        }
    }

    /**
    * Проверяет существование файла отладки debug_ci.cfg в каталоге, где лежит эталонная база
    * @param templateDBPath полный путь к файлу базы (dt иил 1CD)
    * @param nameFileSkip имя файла для пропуска
    * @return true если файл существует, false если не существует
    */
    static boolean isFileDebugExists(String templateDBPath, String nameFileSkip) {
        
        FilePath pathTemplateDBPath = getFilePath(templateDBPath)
        FilePath templateDbParentDir = pathTemplateDBPath.getParent()
        String dirTemplateDbParentDir = templateDbParentDir.getRemote()
        String pathFileDebug = "$dirTemplateDbParentDir/$nameFileSkip"    
        FilePath debugPathFile = getFilePath(pathFileDebug)
        
        return debugPathFile.exists()    
    }

    private static boolean isValidUrl(String url) {
        try {
            new URL(url).toURI()
            return true
        } catch (MalformedURLException | URISyntaxException e) {
            return false
        }
    }

    private static String getAbsolutePath(String path, def env) {
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) {
            return path
        } else {
            return "${env.WORKSPACE}/${path}"
        }
    }

    private static void copyWithSystemTools(FilePath source, FilePath target) {
        
        IStepExecutor steps = ContextRegistry.getContext().getStepExecutor()

        if (steps.isUnix()) {
            // Используем rsync для Linux
            String encoding = 'UTF-8'
            steps.sh("rsync -av --progress ${source.remote} ${target.remote}", false, false , encoding)
        } else {
            // Используем robocopy для Windows
            String nameSource = source.getName()
            FilePath parentDir = source.getParent()
            String sourceDirectoryPath = parentDir.getRemote()
            
            FilePath targetParentDir = target.getParent()
            String targetDirectoryPath = targetParentDir.getRemote()
            
            String commandCopy =  "robocopy ${sourceDirectoryPath} ${targetDirectoryPath} ${nameSource} /E /Z /MT:8 /R:3 /W:10"            
            String script = """@echo off
                chcp 65001 > nul
                robocopy "${sourceDirectoryPath}" "${targetDirectoryPath}" "${nameSource}" /E /Z /MT:8 /R:3 /W:10 > nul
                if errorlevel 1 (
                    exit /b 0
                )"""
            steps.bat(script, false, true, 'UTF-8')
            
            Logger.println("Вызов команды копирования: ${commandCopy}")
        }
    }

}
