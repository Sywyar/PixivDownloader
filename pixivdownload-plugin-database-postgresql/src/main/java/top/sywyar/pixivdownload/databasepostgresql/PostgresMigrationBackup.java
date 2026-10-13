package top.sywyar.pixivdownload.databasepostgresql;

import top.sywyar.pixivdownload.plugin.api.database.migration.*;
import top.sywyar.pixivdownload.plugin.api.database.migration.jdbc.JdbcMigrationSupport;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import javax.xml.stream.*;

/** PostgreSQL 一致事务的逻辑备份；格式 1 明确保留 schema、类型、数据与序列，不作为另一端的输入。 */
final class PostgresMigrationBackup {
    private PostgresMigrationBackup() {}
    static void write(Connection connection, MigrationRequest request, Map<String, Long> sequences,
                      MigrationControl control) throws Exception {
        MigrationFiles.createPrivateFile(request.backup());
        try (var output = Files.newOutputStream(request.backup(), StandardOpenOption.WRITE)) {
            var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
            try {
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("database-backup");
                xml.writeAttribute("format", "1");
                xml.writeAttribute("provider-format", "postgresql-logical");
                for (var table : request.schema().tables()) {
                    xml.writeStartElement("table"); xml.writeAttribute("name", table.name());
                    if (!PostgresMigrationSchema.renderCheck(table).isEmpty())
                        xml.writeAttribute("check", PostgresMigrationSchema.renderCheck(table));
                    xml.writeAttribute("high-water", Long.toString(sequences.getOrDefault(table.name(), 0L)));
                    for (var column : table.columns()) {
                        xml.writeEmptyElement("column");
                        xml.writeAttribute("name", column.name()); xml.writeAttribute("type", column.type());
                        xml.writeAttribute("not-null", Boolean.toString(column.notNull()));
                        xml.writeAttribute("primary-key", Integer.toString(column.primaryKeyPosition()));
                        xml.writeAttribute("auto-increment", Boolean.toString(column.autoIncrement()));
                        if (column.defaultValue() != null) xml.writeAttribute("default", column.defaultValue());
                    }
                    for (var index : table.indexes()) {
                        xml.writeEmptyElement("index");
                        if (index.name() != null) xml.writeAttribute("name", index.name());
                        xml.writeAttribute("origin", index.origin().name()); xml.writeAttribute("unique", Boolean.toString(index.unique()));
                        xml.writeAttribute("columns", String.join(",", index.columns()));
                    }
                    try (var rows = PostgresMigration.rows(connection, table)) {
                        long count = 0;
                        while (rows.next()) {
                            control.checkpoint(table.name(), count++);
                            xml.writeStartElement("row");
                            for (Object value : rows.values()) {
                                xml.writeStartElement("value");
                                if (value == null) xml.writeAttribute("null", "true");
                                else if (value instanceof byte[] bytes) {
                                    xml.writeAttribute("encoding", "base64");
                                    xml.writeCharacters(Base64.getEncoder().encodeToString(bytes));
                                } else if (value instanceof String text) {
                                    // TEXT 可含 XML 不接受的控制字符，UTF-8 的 Base64 保持逐字节无损。
                                    xml.writeAttribute("encoding", "utf8-base64");
                                    xml.writeCharacters(Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                                } else xml.writeCharacters(value.toString());
                                xml.writeEndElement();
                            }
                            xml.writeEndElement();
                        }
                    }
                    xml.writeEndElement();
                }
                for (var definition : PostgresMigration.objectDefinitions(connection).entrySet()) {
                    xml.writeStartElement("postgresql-object"); xml.writeAttribute("name", definition.getKey());
                    xml.writeCharacters(definition.getValue()); xml.writeEndElement();
                }
                for (String object : request.rebuildObjects().stream().sorted().toList()) {
                    xml.writeEmptyElement("rebuild"); xml.writeAttribute("name", object);
                }
                xml.writeEndElement(); xml.writeEndDocument(); xml.flush();
            } finally { xml.close(); }
        }
        try (var channel = java.nio.channels.FileChannel.open(request.backup(), StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
