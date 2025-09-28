package ru.pulsar.jenkins.library.steps

import ru.pulsar.jenkins.library.edt.EdtCliEngineFactory
import ru.pulsar.jenkins.library.IStepExecutor
import ru.pulsar.jenkins.library.configuration.JobConfiguration
import ru.pulsar.jenkins.library.configuration.SourceFormat
import ru.pulsar.jenkins.library.ioc.ContextRegistry
import ru.pulsar.jenkins.library.utils.FileUtils
import ru.pulsar.jenkins.library.utils.Logger

class EdtToDesignerFormatTransformation implements Serializable {

    public static final String WORKSPACE = 'build/edt-workspace'
    public static final String CONFIGURATION_DIR = 'build/cfg'
    public static final String CONFIGURATION_ZIP = 'build/cfg.zip'
    public static final String CONFIGURATION_ZIP_STASH = 'cfg-zip'
    public static final String EXTENSION_DIR = 'build/cfe_src'
    public static final String EXTENSION_ZIP = 'build/cfe_src.zip'
    public static final String EXTENSION_ZIP_STASH = 'cfe_src-zip'

    private final JobConfiguration config;

    EdtToDesignerFormatTransformation(JobConfiguration config) {
        this.config = config
    }

    def run() {
        IStepExecutor steps = ContextRegistry.getContext().getStepExecutor()

        Logger.printLocation()

        if (config.sourceFormat != SourceFormat.EDT) {
            Logger.println("SRC is not in EDT format. No transform is needed.")
            return
        }
      
        def env = steps.env();

        String workspaceDir = FileUtils.getFilePath("$env.WORKSPACE/$WORKSPACE").getRemote()
        Logger.println("Очистка каталога $workspaceDir")
        steps.deleteDir(workspaceDir)

    
        def engine = EdtCliEngineFactory.getEngine(config.edtVersion)
        
        // * Каратаев Олег - Возможность пропуска этапа по наличию файла отладки
        // Инициализация файлов пропусков этапов и помещение их в стейдж       
        String templateDBPath = config.initInfoBaseOptions.templateDBPath
        FileUtils.isFileDebugExists(templateDBPath, "skip_ci_sonar.cfg")
        FileUtils.isFileDebugExists(templateDBPath, "skip_ci_syntax.cfg")
        Boolean isFileDebug = FileUtils.isFileDebugExists(templateDBPath, "debug_ci.cfg")
        if (isFileDebug) {
           Logger.println("Пропуск конвертации конфигурации из ЕДТ в формат конфигуратора. Найден файл отладки debug_ci.cfg")
        } else {
           // Конвертация конфигурации из ЕДТ в формат конфигуратора.
            engine.edtToDesignerTransformConfiguration(steps, config)
            steps.zip(CONFIGURATION_DIR, CONFIGURATION_ZIP)
            steps.stash(CONFIGURATION_ZIP_STASH, CONFIGURATION_ZIP)
        }
        // *
   
        if (config.needLoadExtensions()) {
            //  Конвертация расширений из ЕДТ в формат конфигуратора.
            // * Каратаев Олег - Возможность пропуска этапа по наличию файла отладки
            if (isFileDebug) {
                Logger.println("Пропуск конвертации расширений из ЕДТ в формат конфигуратора. Найден файл отладки debug_ci.cfg")
            } else {
                engine.edtToDesignerTransformExtensions(steps, config)
            }
            //engine.edtToDesignerTransformExtensions(steps, config)
            // *
            steps.zip(EXTENSION_DIR, EXTENSION_ZIP)
            steps.stash(EXTENSION_ZIP_STASH, EXTENSION_ZIP)
        }

    }

}
