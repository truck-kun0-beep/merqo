import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Proves why the catalogue search fails: a NULL parameter of an untyped query is
 * inferred by PostgreSQL as bytea, and lower(bytea) does not exist.
 */
public class ParamProbe {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:5432/merqo_test";
        try (Connection conn = DriverManager.getConnection(url, "postgres", "postgres")) {
            probe(conn, "the current shape: (? is null or lower(sku) like ...)",
                    "select count(*) from product_variants where (? is null or lower(sku) like lower(('%'||?||'%')))",
                    null);
            probe(conn, "the fix: coalesce(?::varchar, '') = ''",
                    "select count(*) from product_variants where (coalesce(?::varchar, '') = '' or lower(sku) like lower(('%'||?||'%')))",
                    null);
            probe(conn, "a typed parameter with the same shape",
                    "select count(*) from product_variants where (?::varchar is null or lower(sku) like lower(('%'||?||'%')))",
                    null);
        }
    }

    private static void probe(Connection conn, String label, String sql, String value)
            throws Exception {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            if (value == null) {
                statement.setNull(1, java.sql.Types.NULL);
            } else {
                statement.setString(1, value);
            }
            statement.setString(2, value == null ? "" : value);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                System.out.println("OK   " + label + " -> " + result.getLong(1));
            }
        } catch (Exception failure) {
            System.out.println("FAIL " + label + " -> " + failure.getMessage().split("\n")[0]);
        }
    }
}
