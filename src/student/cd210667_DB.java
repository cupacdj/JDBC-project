package src.student;

import java.sql.*;

public class cd210667_DB {

    private static final String URL = System.getenv().getOrDefault("MSSQL_URL", "jdbc:sqlserver://127.0.0.1;databaseName=Filmovi2;encrypt=false;trustServerCertificate=true;");
    private static final String USER = System.getenv().getOrDefault("MSSQL_USER", "cupac");
    private static final String PASS = System.getenv().getOrDefault("MSSQL_PASS", "1234");

    private static Connection connection;

    public static Connection getConnection() throws SQLException {
        if (connection == null)
            connection = DriverManager.getConnection(URL, USER, PASS);
        return connection;
    }

}
//127.0.0.1