package com.scivicslab.k8spups.dataset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsing of the lines of the two import files. Pure logic: a bad line is reported, never guessed at.
 * Blank lines and lines starting with '#' are ignored; the separator is a comma.
 */
public final class CsvLines {

    private CsvLines() {}

    /** One catalog line: a file of a dataset. */
    public record CatalogEntry(String dataset, String path) {}

    /** One access line: a user and a dataset, with an optional last day of validity. */
    public record AccessEntry(String user, String dataset, Instant expiresAt) {}

    /** The result of parsing: the good entries and a message per bad line. */
    public record Parsed<T>(List<T> entries, List<String> errors) {}

    /**
     * Catalog lines: {@code dataset,path} or just {@code path} (the dataset is then the first path segment).
     * The path is relative to the original directory and must start with the dataset name.
     */
    public static Parsed<CatalogEntry> catalog(Iterable<String> lines) {
        List<CatalogEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            String dataset, path;
            if (f.length == 1) {
                path = f[0].strip();
                dataset = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            } else if (f.length == 2) {
                dataset = f[0].strip();
                path = f[1].strip();
            } else {
                errors.add("line " + n + ": expected 'dataset,path' or 'path'");
                continue;
            }
            if (!DatasetPath.isValidRelativePath(dataset) || dataset.contains("/")) {
                errors.add("line " + n + ": invalid dataset name '" + dataset + "'");
            } else if (!DatasetPath.isValidRelativePath(path) || !path.startsWith(dataset + "/")) {
                errors.add("line " + n + ": the path must be relative and start with '" + dataset + "/'");
            } else {
                entries.add(new CatalogEntry(dataset, path));
            }
        }
        return new Parsed<>(entries, errors);
    }

    /** Access lines: {@code user,dataset} or {@code user,dataset,YYYY-MM-DD} (valid through that day, UTC). */
    public static Parsed<AccessEntry> access(Iterable<String> lines) {
        List<AccessEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            if (f.length < 2 || f.length > 3) {
                errors.add("line " + n + ": expected 'user,dataset' or 'user,dataset,YYYY-MM-DD'");
                continue;
            }
            String user = f[0].strip();
            String dataset = f[1].strip();
            Instant expires = null;
            if (f.length == 3 && !f[2].isBlank()) {
                try {
                    expires = LocalDate.parse(f[2].strip()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (Exception e) {
                    errors.add("line " + n + ": invalid date '" + f[2].strip() + "'");
                    continue;
                }
            }
            if (!DatasetPath.isValidUserName(user)) {
                errors.add("line " + n + ": invalid user name '" + user + "'");
            } else if (!DatasetPath.isValidRelativePath(dataset) || dataset.contains("/")) {
                errors.add("line " + n + ": invalid dataset name '" + dataset + "'");
            } else {
                entries.add(new AccessEntry(user, dataset, expires));
            }
        }
        return new Parsed<>(entries, errors);
    }
}
