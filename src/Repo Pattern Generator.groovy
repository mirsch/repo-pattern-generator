import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasTable
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.Case
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

import com.intellij.database.view.generators.Files

/*
 * Available context bindings:
 *   SELECTION   Iterable<com.intellij.database.model.DasObject> -- selected entries in the tree
 *   PROJECT     com.intellij.openapi.project.Project -- currently opened project
 *   FILES       com.intellij.database.view.generators.Files -- files helper
 */


public class PHPRepositoryPatternGenerator {
    public void start(Settings settings, Iterable<DasObject> selection, File targetDirectory) {
        selection
                .filter {
            it instanceof DasTable && it.getKind() == ObjectKind.TABLE
        }
        .find()
                .each { DasTable table ->
            String repositoryContent = generateRepository(table, settings)
            writeToDirectory(targetDirectory, toPHPClassName(table) + "Repository.php", repositoryContent)

            String entityContent = generateEntity(table, settings)
            writeToDirectory(targetDirectory, toPHPClassName(table) + "Entity.php", entityContent)
        }
    }


    private void writeToDirectory(File directory, String fileName, String content) {
        new File(directory, fileName).write(content)
    }

    private String generateEntity(DasTable table, Settings settings) {
        String className = toPHPClassName(table)
        String useClause = ""
        if (hasDateTime(table)) {
            useClause = "\n            use DateTime;\n"
        }

        String properties = getProperties(table)
        String getterAndSetter = getGetterAndSetter(table)

        """\
            <?php declare(strict_types = 1);

            namespace ${settings.namespace};
            ${useClause}
            class ${className}Entity
            {            
                ${properties}                
                ${getterAndSetter}
            }
        """.stripIndent()
    }

    private String generateRepository(DasTable table, Settings settings) {
        String className = toPHPClassName(table)
        String useClause = "use Doctrine\\DBAL\\Connection;"
        if (hasDateTime(table)) {
            useClause = "use DateTime;\n            " + useClause
        }

        String primary = getPrimaryColName(table)
        String primaryPropertyName = toPropertyName(primary)
        String entityToDatabase = getEntityToDatabase(table)
        String databaseToEntity = getDatabaseToEntity(table)
        """\
            <?php declare(strict_types = 1);

            namespace ${settings.namespace};
            
            ${useClause}
            
            class ${className}Repository
            {
            
                protected const TABLE = '${table.getName()}';
            
                /**
                 * @var \\Doctrine\\DBAL\\Connection
                 */
                protected \$connection;
            
                public function __construct(Connection \$connection)
                {
                    \$this->connection = \$connection;
                }
            
                /**
                 * Fetches all entities by given where clause.
                 *
                 * @param array \$where
                 * @param null|int \$limit
                 * @param null|int \$offset
                 *
                 * @return null|\\${settings.namespace}\\${className}Entity[]
                 */
                public function findBy(array \$where, ?int \$limit = null, ?int \$offset = null)
                {
                    \$queryBuilder = \$this->connection->createQueryBuilder()
                         ->select('*')
                         ->from(self::TABLE);
                    foreach (\$where as \$column => \$value) {
                        \$queryBuilder->andWhere(\$column . ' = ' . \$queryBuilder->createNamedParameter(\$value));
                    }
                    if (\$limit !== null) {
                        \$queryBuilder->setMaxResults(\$limit);
                    }
                    if (\$offset !== null) {
                        \$queryBuilder->setFirstResult(\$offset);
                    }
                    \$statement = \$queryBuilder->execute();
                    \$result = \$statement->fetchAll();
            
                    if (\$statement->rowCount() === 0) {
                        return null;
                    }
            
                    \$entities = [];
                    foreach (\$result as \$dbarray) {
                        \$entities[] = \$this->databaseArrayToEntity(\$dbarray);
                    }
            
                    return \$entities;
                }
            
                /**
                 * @param array \$where
                 *
                 * @return null|\\${settings.namespace}\\${className}Entity
                 */
                public function findOneBy(array \$where): ?${className}Entity
                {
                    \$entities = \$this->findBy(\$where, 1);
                    if (\$entities === null) {
                        return null;
                    }
            
                    return \$entities[0];
                }
            
                /**
                 * @param int \$${primaryPropertyName}
                 *
                 * @return null|\\${settings.namespace}\\${className}Entity
                 */
                public function findByPk(\$${primaryPropertyName}): ?${className}Entity
                {
                    return \$this->findOneBy(['${primary}' => \$${primaryPropertyName}]);
                }
            
                public function createEntity()
                {
                    return new ${className}Entity();
                }
            
                /**
                 * @param \\${settings.namespace}\\${className}Entity \$entity
                 *
                 * @return int The number of affected rows.
                 */
                public function save(${className}Entity &\$entity): int
                {
                    \$databaseArray = \$this->entityToDatabaseArray(\$entity);
            
                    if (\$entity->get${primaryPropertyName.capitalize()}() === null) {
                        \$affected = \$this->connection->insert(
                            self::TABLE,
                            \$databaseArray
                        );
                        \$entity->set${primaryPropertyName.capitalize()}((int)\$this->connection->lastInsertId());
            
                        return \$affected;
                    }
            
                    return \$this->connection->update(
                        self::TABLE,
                        \$databaseArray,
                        ['${primary}' => \$entity->get${primaryPropertyName.capitalize()}()]
                    );
                }
            
                protected function entityToDatabaseArray(${className}Entity \$entity): array
                {
                    return [
                        ${entityToDatabase}
                    ];
                }
            
                protected function databaseArrayToEntity(array \$data): ${className}Entity
                {
                    \$entity = new ${className}Entity();
            
                    ${databaseToEntity}
            
                    return \$entity;
                }
            
            }
            """.stripIndent()
    }

    private String getProperties(DasTable table) {
        String content = ""
        DasUtil.getColumns(table).eachWithIndex { it, i ->
            String type = getType(it) + (it.isNotNull() ? '' : '|null')
            String propertyName = toPropertyName(it.getName())
            String line =
                """
                /**
                 * @var ${type}
                 */
                protected \$${propertyName};
                """
            if (i != 0) {
                content += "    "
            }
            content += line
        }
        return content
    }

    private String getGetterAndSetter(DasTable table) {
        String content = ""
        String className = toPHPClassName(table)
        DasUtil.getColumns(table).eachWithIndex { it, i ->
            String type = getType(it) + (it.isNotNull() ? '' : '|null')
            String propertyName = toPropertyName(it.getName())
            String returnType = ((it.isNotNull() && !DasUtil.isPrimary(it)) ? "" : "?") + getType(it)
            String line =
                """
                public function get${propertyName.capitalize()}(): ${returnType}
                {
                    return \$this->${propertyName};
                }
                
                public function set${propertyName.capitalize()}(${returnType} \$${propertyName}): ${className}Entity
                {
                    \$this->${propertyName} = \$${propertyName};
                
                    return \$this;
                }
                """
            if (i != 0) {
                content += "    "
            }
            content += line
        }
        return content
    }

    private String getType(DasColumn col) {
        def typeMapping = [
                (~/(?i)tinyint/)                  : "bool",
                (~/(?i)int/)                      : "int",
                (~/(?i)float|double|decimal|real/): "float",
                (~/(?i)datetime|timestamp/)       : "DateTime",
                (~/(?i)date/)                     : "DateTime",
                (~/(?i)time/)                     : "DateTime",
                (~/(?i)/)                         : "string"
        ]

        def spec = Case.LOWER.apply(col.getDataType().getSpecification())
        return typeMapping.find { p, t -> p.matcher(spec).find() }.value
    }

    private String getEntityToDatabase(DasTable table) {
        String content = ""
        DasUtil.getColumns(table).eachWithIndex { it, i ->
            def getter = "get" + toPropertyName(it.getName()).capitalize()
            String line
            if (getType(it) == "bool") {
                line = "'${it.getName()}' => (int)\$entity->${getter}(),"
            } else if (getType(it) == "DateTime") {
                if (!it.isNotNull()) {
                    line = "'${it.getName()}' => \$entity->${getter}() !== null ? \$entity->${getter}()->format(DateTime::ATOM) : null,"
                } else {
                    line = "'${it.getName()}' => \$entity->${getter}()->format(DateTime::ATOM),"
                }
            } else {
                line = "'${it.getName()}' => \$entity->${getter}(),"
            }
            if (i != 0) {
                content += "                        "
            }
            content += line
            if (i !=  DasUtil.getColumns(table).size()-1) {
                content += "\n"
            }
        }
        return content
    }

    private String getDatabaseToEntity(DasTable table) {
        String content = ""
        DasUtil.getColumns(table).eachWithIndex { it, i ->
            String setter = "set" + toPropertyName(it.getName()).capitalize()
            String type = getType(it)
            String line
            if (!it.isNotNull()) {
                if (getType(it) == "DateTime") {
                    line = "\$entity->${setter}(\$data['${it.getName()}'] !== null ? new DateTime(\$data['${it.getName()}']) : null);"
                } else {
                    line = "\$entity->${setter}(\$data['${it.getName()}'] !== null ? (${type})\$data['${it.getName()}'] : null);"
                }
            } else {
                if (getType(it) == "DateTime") {
                    line = "\$entity->${setter}(new DateTime(\$data['${it.getName()}']));"
                } else {
                    line = "\$entity->${setter}((${type})\$data['${it.getName()}']);"
                }
            }

            if (i != 0) {
                content += "                    "
            }
            content += line
            if (i !=  DasUtil.getColumns(table).size()-1) {
                content += "\n"
            }
        }
        return content
    }

    private boolean hasDateTime(DasTable table) {
        boolean hasDateTime = false
        DasUtil.getColumns(table).each() {
            if (getType(it) == "DateTime") {
                hasDateTime = true
            }
        }
        return hasDateTime
    }

    private String getPrimaryColName(DasTable table) {
        def primary = ""
        DasUtil.getColumns(table).each() {
            if (DasUtil.isPrimary(it)) {
                primary = it.getName()
            }
        }
        return primary
    }

    private CharSequence toCamelCase(CharSequence input) {
        return input
                .split('_')
                .collect { it.capitalize() }
                .join('')
    }

    private CharSequence toPropertyName(CharSequence input) {
        def words = input
                .split('_')
                .collect { it.capitalize() }
        if (words.size() > 1) {
            return (words[0].toLowerCase() + words[1..-1].join(""))
        } else {
            return words[0].toLowerCase();
        }
    }

    private CharSequence toPHPClassName(DasTable table) {
        return toCamelCase(table.name)
    }
}


class Settings {
    String namespace
}


import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.GridLayout
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent
import com.intellij.openapi.application.ApplicationManager


class ExportSettingsDialog extends DialogWrapper {
    JLabel namespaceLabel = new JLabel("Namespace")
    JTextField namespaceTextField = new JTextField("Example\\Persistence")

    protected ExportSettingsDialog(Project project) {
        super(project)
        init()
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 5));
        panel.add(this.namespaceLabel)
        panel.add(this.namespaceTextField)

        return panel;
    }
}


ApplicationManager.getApplication().invokeLater {
    def dialog = new ExportSettingsDialog(PROJECT)
    dialog.show()
    if (dialog.exitCode == DialogWrapper.OK_EXIT_CODE) {
        FILES.chooseDirectoryAndSave("Choose directory", "Choose the directory for generated files") { directory ->
            def settings = new Settings()
            settings.namespace = dialog.namespaceTextField.text

            PHPRepositoryPatternGenerator generator = new PHPRepositoryPatternGenerator()
            generator.start(settings, SELECTION, directory)
        }
    }
}
