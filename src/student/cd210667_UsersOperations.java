package src.student;

import rs.ac.bg.etf.sab.operations.UsersOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_UsersOperations implements UsersOperations {

    private Connection connection;

    public cd210667_UsersOperations() {
        ensureConnection();
    }

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = cd210667_DB.getConnection(); // tvoja klasa za konekciju
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB connection open failed", e);
        }
    }

    /* =======================
       Helperi
    ======================= */

    private boolean userIdExists(int userId) throws SQLException {
        String sql = "SELECT 1 FROM Korisnik WHERE KorisnikID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean userNameExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM Korisnik WHERE KorisnickoIme = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Integer fetchUserId(String username) throws SQLException {
        String sql = "SELECT KorisnikID FROM Korisnik WHERE KorisnickoIme = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /* =======================
       Implementacije
    ======================= */

    @Override
    public Integer addUser(String username) {
        if (username == null || username.isEmpty()) return null;
        ensureConnection();
        try {
            // duplikat -> null
            if (userNameExists(username)) return null;

            String ins = "INSERT INTO Korisnik (KorisnickoIme) VALUES (?)";
            try (PreparedStatement ps = connection.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            return null;
        } catch (SQLException e) {
            // u slučaju unique violation i sl. vrati null (tako test očekuje za duplicate)
            return null;
        }
    }

    @Override
    public Integer updateUser(Integer userId, String newUsername) {
        if (userId == null || newUsername == null || newUsername.isEmpty()) return null;
        ensureConnection();
        try {
            if (!userIdExists(userId)) return null;

            // Ako postoji drugi korisnik sa tim imenom -> fail (unique)
            String sqlDup = "SELECT 1 FROM Korisnik WHERE KorisnickoIme = ? AND KorisnikID <> ?";
            try (PreparedStatement ps = connection.prepareStatement(sqlDup)) {
                ps.setString(1, newUsername);
                ps.setInt(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return null; // pokušaj na postojeće ime
                }
            }

            String upd = "UPDATE Korisnik SET KorisnickoIme = ? WHERE KorisnikID = ?";
            try (PreparedStatement ps = connection.prepareStatement(upd)) {
                ps.setString(1, newUsername);
                ps.setInt(2, userId);
                int rows = ps.executeUpdate();
                return rows > 0 ? userId : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public Integer removeUser(Integer userId) {
        if (userId == null) return null;
        ensureConnection();
        try {
            String del = "DELETE FROM Korisnik WHERE KorisnikID = ?";
            try (PreparedStatement ps = connection.prepareStatement(del)) {
                ps.setInt(1, userId);
                int rows = ps.executeUpdate();
                return rows > 0 ? userId : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public boolean doesUserExist(String username) {
        if (username == null || username.isEmpty()) return false;
        ensureConnection();
        try {
            return userNameExists(username);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public Integer getUserId(String username) {
        if (username == null || username.isEmpty()) return null;
        ensureConnection();
        try {
            return fetchUserId(username);
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public List<Integer> getAllUserIds() {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        String sql = "SELECT KorisnikID FROM Korisnik ORDER BY KorisnikID";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt(1));
        } catch (SQLException ignored) {}
        return out;
    }

    /* =======================
       Nisu pokriveni testovima
       (bezbedne, jednostavne verzije)
    ======================= */

    @Override
    public List<Integer> getRecommendedMoviesFromFavoriteGenres(Integer userId) {
        List<Integer> out = new ArrayList<>();
        if (userId == null) return out;
        ensureConnection();

        final String sql =
                "WITH UserGenreAvg AS ( " +
                        "  SELECT fz.ZanrID, AVG(CAST(o.Ocena AS DECIMAL(10,4))) AS avgGenre " +
                        "  FROM dbo.Ocena o " +
                        "  JOIN dbo.FilmZanr fz ON fz.FilmID = o.FilmID " +
                        "  WHERE o.KorisnikID = ? " +
                        "  GROUP BY fz.ZanrID " +
                        "), FavGenres AS ( " +
                        "  SELECT ZanrID FROM UserGenreAvg WHERE avgGenre >= 8 " +
                        "), RatedByUser AS ( " +
                        "  SELECT FilmID FROM dbo.Ocena WHERE KorisnikID = ? " +
                        "), InAnyWatchlist AS ( " +
                        "  SELECT lgs.FilmID " +
                        "  FROM dbo.ListaGledanjaStavka lgs " +
                        "  JOIN dbo.ListaGledanja lg ON lg.ListaGledanjaID = lgs.ListaGledanjaID " +
                        "  WHERE lg.KorisnikID = ? " +
                        "), Candidates AS ( " +
                        "  SELECT DISTINCT fz.FilmID " +
                        "  FROM dbo.FilmZanr fz " +
                        "  WHERE fz.ZanrID IN (SELECT ZanrID FROM FavGenres) " +
                        "    AND fz.FilmID NOT IN (SELECT FilmID FROM RatedByUser) " +
                        "    AND fz.FilmID NOT IN (SELECT FilmID FROM InAnyWatchlist) " +
                        ") " +
                        "SELECT c.FilmID " +
                        "FROM Candidates c " +
                        "JOIN dbo.Ocena o ON o.FilmID = c.FilmID " +
                        "GROUP BY c.FilmID " +
                        "HAVING (COUNT(*) >= 4 AND AVG(CAST(o.Ocena AS DECIMAL(10,4))) >= 7.5) " +
                        "    OR (COUNT(*) < 4 AND AVG(CAST(o.Ocena AS DECIMAL(10,4))) >= 9) " +
                        "ORDER BY AVG(CAST(o.Ocena AS DECIMAL(10,4))) DESC, c.FilmID ASC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException ignored) {}
        return out;
    }

    @Override
    public Integer getRewards(Integer userId) {
        if (userId == null) return null;
        ensureConnection();

        // (Opcioni) ako želiš da vratiš null za nepostojećeg korisnika:
        try (PreparedStatement chk = connection.prepareStatement(
                "SELECT 1 FROM dbo.Korisnik WHERE KorisnikID = ?")) {
            chk.setInt(1, userId);
            try (ResultSet rs = chk.executeQuery()) {
                if (!rs.next()) return null;
            }
        } catch (SQLException e) {
            return null;
        }

        final String sql =
                // 1) proseci korisnika po žanrovima
                "WITH UserGenreAvg AS ( " +
                        "  SELECT fz.ZanrID, AVG(CAST(o.Ocena AS DECIMAL(10,4))) AS avgGenre " +
                        "  FROM dbo.Ocena o " +
                        "  JOIN dbo.FilmZanr fz ON fz.FilmID = o.FilmID " +
                        "  WHERE o.KorisnikID = ? " +
                        "  GROUP BY fz.ZanrID " +
                        "), " +
                        // 2) omiljeni žanrovi (avg >= 8)
                        "FavGenres AS ( " +
                        "  SELECT ZanrID FROM UserGenreAvg WHERE avgGenre >= 8 " +
                        "), " +
                        // 3) sve korisnikove ocene sa rednim brojem po vremenu (od 10. nadalje se razmatra nagrada)
                        "UserRatings AS ( " +
                        "  SELECT o.KorisnikID, o.FilmID, o.Ocena, o.Vreme, " +
                        "         ROW_NUMBER() OVER (PARTITION BY o.KorisnikID ORDER BY o.Vreme, o.FilmID) AS rn " +
                        "  FROM dbo.Ocena o " +
                        "  WHERE o.KorisnikID = ? " +
                        ") " +
                        // 4) broj nagrada: film je u omiljenim žanrovima + globalni prosek (bez tog korisnika) < 6
                        "SELECT COUNT(*) AS rewardsCnt " +
                        "FROM ( " +
                        "  SELECT ur.FilmID, " +
                        "         (SELECT AVG(CAST(o2.Ocena AS DECIMAL(10,4))) " +
                        "          FROM dbo.Ocena o2 " +
                        "          WHERE o2.FilmID = ur.FilmID AND o2.KorisnikID <> ur.KorisnikID) AS avgExcl " +
                        "  FROM UserRatings ur " +
                        "  WHERE ur.rn >= 10 " +
                        "    AND EXISTS ( " +
                        "      SELECT 1 " +
                        "      FROM dbo.FilmZanr fz " +
                        "      WHERE fz.FilmID = ur.FilmID " +
                        "        AND fz.ZanrID IN (SELECT ZanrID FROM FavGenres) " +
                        "    ) " +
                        ") q " +
                        "WHERE q.avgExcl IS NOT NULL AND q.avgExcl < 6";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);   // npr. za Alice treba da vrati 1
                return 0;
            }
        } catch (SQLException e) {
            return null;
        }
    }



    @Override
    public List<String> getThematicSpecializations(Integer userId) {
        List<String> out = new ArrayList<>();
        if (userId == null) return out;
        ensureConnection();

        final String sql =
                "SELECT t.Naziv " +
                        "FROM dbo.Ocena o " +
                        "JOIN dbo.FilmTag ft ON ft.FilmID = o.FilmID " +
                        "JOIN dbo.Tag t ON t.TagID = ft.TagID " +
                        "WHERE o.KorisnikID = ? AND o.Ocena >= 8 " +
                        "GROUP BY t.Naziv " +
                        "HAVING COUNT(DISTINCT o.FilmID) >= 2 " +
                        "ORDER BY t.Naziv";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException ignored) {}
        return out;
    }


    @Override
    public String getUserDescription(Integer userId) {
        if (userId == null) return null;
        ensureConnection();

        // broj ocenjenih filmova
        int rated = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM dbo.Ocena WHERE KorisnikID = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) rated = rs.getInt(1); }
        } catch (SQLException e) { return null; }

        if (rated < 10) return "nedefinisan";

        int distinctTags = 0;
        final String sqlTags =
                "SELECT COUNT(DISTINCT t.TagID) " +
                        "FROM dbo.Ocena o " +
                        "JOIN dbo.FilmTag ft ON ft.FilmID = o.FilmID " +
                        "JOIN dbo.Tag t ON t.TagID = ft.TagID " +
                        "WHERE o.KorisnikID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlTags)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) distinctTags = rs.getInt(1); }
        } catch (SQLException e) { return null; }

        return (distinctTags >= 10) ? "radoznao" : "focused";
    }

}
