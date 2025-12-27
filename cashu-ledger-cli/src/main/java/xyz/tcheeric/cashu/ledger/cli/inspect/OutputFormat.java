package xyz.tcheeric.cashu.ledger.cli.inspect;

public enum OutputFormat {
    TEXT,
    JSON,
    TREE,
    CSV;

    public static OutputFormat from(String value) {
        if (value == null) {
            return TEXT;
        }
        return switch (value.toLowerCase()) {
            case "json" -> JSON;
            case "tree" -> TREE;
            case "csv" -> CSV;
            default -> TEXT;
        };
    }
}
