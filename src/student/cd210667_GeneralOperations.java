package src.student;

import rs.ac.bg.etf.sab.operations.GeneralOperations;
import java.sql.*;

import java.util.logging.Level;
import java.util.logging.Logger;

public class cd210667_GeneralOperations implements GeneralOperations {

    private final Connection connection;

    public cd210667_GeneralOperations() {
        try {            this.connection = cd210667_DB.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open DB connection", e);
        }
    }

    @Override
    public void eraseAll() {
        String sql =
                "BEGIN " +
                        "   DELETE FROM dbo.ListaGledanjaStavka; " +
                        "   DELETE FROM dbo.FilmTag; " +
                        "   DELETE FROM dbo.FilmZanr; " +
                        "   DELETE FROM dbo.UserRewardLog; " +
                        "   DELETE FROM dbo.Ocena; " +
                        "   DELETE FROM dbo.ListaGledanja; " +
                        "   DELETE FROM dbo.Film; " +
                        "   DELETE FROM dbo.Tag; " +
                        "   DELETE FROM dbo.Zanr; " +
                        "   DELETE FROM dbo.Reziser; " +
                        "   DELETE FROM dbo.Korisnik; " +
                        "END";

        try (CallableStatement cs = connection.prepareCall(sql)) {
            cs.execute();
        } catch (SQLException ex) {
            Logger.getLogger(cd210667_GeneralOperations.class.getName())
                    .log(Level.SEVERE, null, ex);
            throw new RuntimeException("Failed to erase all data", ex);
        }
    }
}
