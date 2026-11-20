import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Drops and recreates a local PostgreSQL database. Used to prove the test suite
 * passes from an empty database.
 */
public class RecreateDb {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
    try (Connection conn = DriverManager.getConnection(url, "postgres", "");
        Statement st = conn.createStatement()) {
      st.executeUpdate("DROP DATABASE IF EXISTS " + args[0] + " WITH (FORCE)");
      st.executeUpdate("CREATE DATABASE " + args[0]);
      System.out.println("RECREATED " + args[0]);
    }
  }
}
