package src.student;

import rs.ac.bg.etf.sab.operations.MoviesOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_MoviesOperations implements MoviesOperations {

    private Connection connection;

    public cd210667_MoviesOperations() {
        ensureConnection();
    }

    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = cd210667_DB.getConnection();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB connection open failed", e);
        }
    }

    /* ========== helpers ========== */

    private Integer getDirectorIdByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ReziserID FROM dbo.Reziser WHERE ImePrezime = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private Integer ensureDirector(String name) throws SQLException {
        Integer id = getDirectorIdByName(name);
        if (id != null) return id;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO dbo.Reziser(ImePrezime) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private boolean genreExists(Integer genreId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Zanr WHERE ZanrID = ?")) {
            ps.setInt(1, genreId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private boolean movieExists(Integer movieId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Film WHERE FilmID = ?")) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private Integer getMovieDirectorId(Integer movieId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ReziserID FROM dbo.Film WHERE FilmID = ?")) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private String getMovieTitle(Integer movieId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Naslov FROM dbo.Film WHERE FilmID = ?")) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /* ========== interface methods ========== */

    @Override
    public Integer addMovie(String title, Integer genreId, String directorName) {
        if (title == null || title.isBlank() || genreId == null || directorName == null || directorName.isBlank())
            return null;
        ensureConnection();

        try {
            // validacija žanra
            if (genreExists(genreId)) return null;

            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);

            // obezbedi rezisera
            Integer reziserId = ensureDirector(directorName);
            if (reziserId == null) {
                connection.setAutoCommit(oldAuto);
                return null;
            }

            // spreči duplikat (Naslov, ReziserID)
            try (PreparedStatement chk = connection.prepareStatement(
                    "SELECT FilmID FROM dbo.Film WHERE Naslov = ? AND ReziserID = ?")) {
                chk.setString(1, title);
                chk.setInt(2, reziserId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) { // već postoji taj film
                        connection.setAutoCommit(oldAuto);
                        return null;
                    }
                }
            }

            Integer filmId = null;
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO dbo.Film(Naslov, ReziserID) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, title);
                ins.setInt(2, reziserId);
                ins.executeUpdate();
                try (ResultSet rs = ins.getGeneratedKeys()) {
                    if (rs.next()) filmId = rs.getInt(1);
                }
            }

            if (filmId == null) {
                connection.rollback();
                connection.setAutoCommit(oldAuto);
                return null;
            }

            // inicijalno mapiranje žanra
            try (PreparedStatement map = connection.prepareStatement(
                    "INSERT INTO dbo.FilmZanr(FilmID, ZanrID) VALUES (?, ?)")) {
                map.setInt(1, filmId);
                map.setInt(2, genreId);
                map.executeUpdate();
            }

            connection.commit();
            connection.setAutoCommit(oldAuto);
            return filmId;

        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("addMovie failed", e);
        }
    }

    @Override
    public Integer updateMovieTitle(Integer movieId, String newTitle) {
        if (movieId == null || newTitle == null || newTitle.isBlank()) return null;
        ensureConnection();

        try {
            if (movieExists(movieId)) return null;

            // sačuvaj aktuelnog reditelja da bi proverio (Naslov, ReziserID) jedinstvo
            Integer reziserId = getMovieDirectorId(movieId);
            if (reziserId == null) return null;

            // postoji li već film sa (newTitle, reziserId)?
            try (PreparedStatement chk = connection.prepareStatement(
                    "SELECT 1 FROM dbo.Film WHERE Naslov = ? AND ReziserID = ? AND FilmID <> ?")) {
                chk.setString(1, newTitle);
                chk.setInt(2, reziserId);
                chk.setInt(3, movieId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) return null; // duplikat
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE dbo.Film SET Naslov = ? WHERE FilmID = ?")) {
                ps.setString(1, newTitle);
                ps.setInt(2, movieId);
                int aff = ps.executeUpdate();
                return aff == 1 ? movieId : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("updateMovieTitle failed", e);
        }
    }

    @Override
    public Integer addGenreToMovie(Integer movieId, Integer genreId) {
        if (movieId == null || genreId == null) return null;
        ensureConnection();

        try {
            if (movieExists(movieId)) return null;
            if (genreExists(genreId)) return null;

            // već postoji veza?
            try (PreparedStatement chk = connection.prepareStatement(
                    "SELECT 1 FROM dbo.FilmZanr WHERE FilmID = ? AND ZanrID = ?")) {
                chk.setInt(1, movieId);
                chk.setInt(2, genreId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) return null; // već je dodat
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dbo.FilmZanr(FilmID, ZanrID) VALUES (?, ?)")) {
                ps.setInt(1, movieId);
                ps.setInt(2, genreId);
                ps.executeUpdate();
                return movieId;
            }
        } catch (SQLException e) {
            throw new RuntimeException("addGenreToMovie failed", e);
        }
    }

    @Override
    public Integer removeGenreFromMovie(Integer movieId, Integer genreId) {
        if (movieId == null || genreId == null) return null;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM dbo.FilmZanr WHERE FilmID = ? AND ZanrID = ?")) {
            ps.setInt(1, movieId);
            ps.setInt(2, genreId);
            int aff = ps.executeUpdate();
            return aff == 1 ? movieId : null;
        } catch (SQLException e) {
            throw new RuntimeException("removeGenreFromMovie failed", e);
        }
    }

    @Override
    public Integer updateMovieDirector(Integer movieId, String newDirectorName) {
        if (movieId == null || newDirectorName == null || newDirectorName.isBlank()) return null;
        ensureConnection();

        try {
            if (movieExists(movieId)) return null;

            String title = getMovieTitle(movieId);
            if (title == null) return null;

            Integer newReziserId = ensureDirector(newDirectorName);
            if (newReziserId == null) return null;

            // da li već postoji film sa istim naslovom i novim rediteljem?
            try (PreparedStatement chk = connection.prepareStatement(
                    "SELECT 1 FROM dbo.Film WHERE Naslov = ? AND ReziserID = ? AND FilmID <> ?")) {
                chk.setString(1, title);
                chk.setInt(2, newReziserId);
                chk.setInt(3, movieId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) return null; // duplikat
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE dbo.Film SET ReziserID = ? WHERE FilmID = ?")) {
                ps.setInt(1, newReziserId);
                ps.setInt(2, movieId);
                int aff = ps.executeUpdate();
                return aff == 1 ? movieId : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("updateMovieDirector failed", e);
        }
    }

    @Override
    public Integer removeMovie(Integer movieId) {
        if (movieId == null) return null;
        ensureConnection();

        try {
            if (movieExists(movieId)) return null;

            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // brišemo sve zavisnosti koje mogu postojati
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.FilmZanr WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.FilmTag WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    ps.executeUpdate();
                } catch (SQLException ignored) {}
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.ListaGledanjaStavka WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    ps.executeUpdate();
                } catch (SQLException ignored) {}
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.Ocena WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    ps.executeUpdate();
                } catch (SQLException ignored) {}
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.UserRewardLog WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    ps.executeUpdate();
                } catch (SQLException ignored) {}

                int aff;
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM dbo.Film WHERE FilmID = ?")) {
                    ps.setInt(1, movieId);
                    aff = ps.executeUpdate();
                }

                connection.commit();
                connection.setAutoCommit(oldAuto);
                return aff == 1 ? movieId : null;

            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(oldAuto);
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("removeMovie failed", e);
        }
    }

    @Override
    public List<Integer> getMovieIds(String title, String directorName) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (title == null || directorName == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.FilmID " +
                        "FROM dbo.Film f " +
                        "JOIN dbo.Reziser r ON r.ReziserID = f.ReziserID " +
                        "WHERE f.Naslov = ? AND r.ImePrezime = ? " +
                        "ORDER BY f.FilmID ASC")) {
            ps.setString(1, title);
            ps.setString(2, directorName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getMovieIds failed", e);
        }
        return out;
    }

    @Override
    public List<Integer> getAllMovieIds() {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT FilmID FROM dbo.Film ORDER BY FilmID ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException("getAllMovieIds failed", e);
        }
        return out;
    }

    @Override
    public List<Integer> getMovieIdsByGenre(Integer genreId) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (genreId == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT fz.FilmID " +
                        "FROM dbo.FilmZanr fz " +
                        "WHERE fz.ZanrID = ? " +
                        "ORDER BY fz.FilmID ASC")) {
            ps.setInt(1, genreId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getMovieIdsByGenre failed", e);
        }
        return out;
    }

    @Override
    public List<Integer> getGenreIdsForMovie(Integer movieId) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (movieId == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT fz.ZanrID " +
                        "FROM dbo.FilmZanr fz " +
                        "WHERE fz.FilmID = ? " +
                        "ORDER BY fz.ZanrID ASC")) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getGenreIdsForMovie failed", e);
        }
        return out;
    }

    @Override
    public List<Integer> getMovieIdsByDirector(String directorName) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (directorName == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT f.FilmID " +
                        "FROM dbo.Film f " +
                        "JOIN dbo.Reziser r ON r.ReziserID = f.ReziserID " +
                        "WHERE r.ImePrezime = ? " +
                        "ORDER BY f.FilmID ASC")) {
            ps.setString(1, directorName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getMovieIdsByDirector failed", e);
        }
        return out;
    }
}
