import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Small utility used once to create the local development databases.
 * It is a plain JDBC program so it needs no Spring context and no extra tooling.
 */
public class CreateDb {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
        try (Connection conn = DriverManager.getConnection(url, "postgres", "");
             Statement st = conn.createStatement()) {
            for (String db : args) {
                ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'");
                if (rs.next()) {
                    System.out.println("EXISTS  " + db);
                } else {
                    st.executeUpdate("CREATE DATABASE \"" + db + "\"");
                    System.out.println("CREATED " + db);
                }
            }
        }
    }
}
