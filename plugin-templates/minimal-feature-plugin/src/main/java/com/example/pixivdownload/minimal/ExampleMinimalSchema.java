package com.example.pixivdownload.minimal;

import top.sywyar.pixivdownload.plugin.api.schema.ColumnSpec;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;

import java.util.List;

/** A minimal plugin-owned table declaration. The host remains the only DDL executor. */
public final class ExampleMinimalSchema {

    private ExampleMinimalSchema() {
    }

    public static SchemaContribution contribution() {
        TableSpec records = new TableSpec(
                "example_minimal_records",
                List.of(new ColumnSpec("id", "TEXT", true, null, 1)),
                List.of());
        return new SchemaContribution(
                ExampleMinimalPlugin.ID,
                List.of(records),
                List.of(),
                List.of(),
                List.of());
    }
}
