import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

public class OCIConnectionTest {

    public static void main(String[] args) {
        // --- Configuration ---
        // String url = "jdbc:oracle:oci:@PDB1ORCL";
        // Below url format is preferred as Oracle XStream CDC Source Connector seems to use this to establish connection
        String url = "jdbc:oracle:oci:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=oracle19c)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=ORCLPDB1)(SERVER=dedicated)))";

        String user = "C##CFLTUSER"; // Replace with your database username
        String password = "password"; // Replace with your database password

        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            System.out.println("Oracle JDBC Driver Registered!");

            System.out.println("Attempting to connect to database...");
            connection = DriverManager.getConnection(url, user, password);
            System.out.println("Connection successful!");

            statement = connection.createStatement();
            // Assuming PDB has an existing TABLE C##CFLTUSER.TEST (id integer, values varchar(10)); with some rows in it
            resultSet = statement.executeQuery("SELECT ID, VALUE FROM C##CFLTUSER.TEST");
            if (resultSet.next()) {
                System.out.println("ID: " + resultSet.getInt(1));
                System.out.println("Value: " + resultSet.getString(2));
            }

        } catch (ClassNotFoundException e) {
            System.err.println("Oracle JDBC Driver not found in classpath. Error: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("Database connection failed! Error Code: " + e.getErrorCode());
            System.err.println("SQL State: " + e.getSQLState());
            System.err.println("Message: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 4. Close resources
            try {
                if (resultSet != null) resultSet.close();
                if (statement != null) statement.close();
                if (connection != null) connection.close();
                System.out.println("Connection closed.");
            } catch (SQLException e) {
                System.err.println("Error closing resources: " + e.getMessage());
            }
        }
    }
}
