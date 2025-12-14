package src.student;

import rs.ac.bg.etf.sab.operations.TagsOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_TagsOperations implements TagsOperations {

    private Connection connection;

    public cd210667_TagsOperations() {
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

    /* ===== Helperi ===== */

    private boolean movieExists(int movieId) throws SQLException {
        String sql = "SELECT 1 FROM Film WHERE FilmID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private Integer getOrCreateTagId(String tagName) throws SQLException {
        String sel = "SELECT TagID FROM Tag WHERE Naziv = ?";
        try (PreparedStatement ps = connection.prepareStatement(sel)) {
            ps.setString(1, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }

        String ins = "INSERT INTO Tag (Naziv) VALUES (?)";
        try (PreparedStatement ps = connection.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tagName);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return null;
    }

    private Integer findTagId(String tagName) throws SQLException {
        String sel = "SELECT TagID FROM Tag WHERE Naziv = ?";
        try (PreparedStatement ps = connection.prepareStatement(sel)) {
            ps.setString(1, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private boolean linkExists(int movieId, int tagId) throws SQLException {
        String sql = "SELECT 1 FROM FilmTag WHERE FilmID = ? AND TagID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, movieId);
            ps.setInt(2, tagId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /* ===== Implementacije interfejsa ===== */

    @Override
    public Integer addTag(Integer movieId, String tagName) {
        if (movieId == null || tagName == null || tagName.isEmpty()) return null;
        ensureConnection();
        try {
            if (movieExists(movieId)) return null;

            Integer tagId = getOrCreateTagId(tagName);
            if (tagId == null) return null;

            if (linkExists(movieId, tagId)) return null;

            String ins = "INSERT INTO FilmTag (FilmID, TagID) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(ins)) {
                ps.setInt(1, movieId);
                ps.setInt(2, tagId);
                ps.executeUpdate();
            }
            return movieId;
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public Integer removeTag(Integer movieId, String tagName) {
        if (movieId == null || tagName == null || tagName.isEmpty()) return null;
        ensureConnection();
        try {
            if (movieExists(movieId)) return null;

            Integer tagId = findTagId(tagName);
            if (tagId == null) return null;

            String del = "DELETE FROM FilmTag WHERE FilmID = ? AND TagID = ?";
            try (PreparedStatement ps = connection.prepareStatement(del)) {
                ps.setInt(1, movieId);
                ps.setInt(2, tagId);
                int rows = ps.executeUpdate();
                return rows > 0 ? movieId : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public int removeAllTagsForMovie(Integer movieId) {
        if (movieId == null) return 0;
        ensureConnection();
        try {
            if (movieExists(movieId)) return 0;

            String del = "DELETE FROM FilmTag WHERE FilmID = ?";
            try (PreparedStatement ps = connection.prepareStatement(del)) {
                ps.setInt(1, movieId);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public boolean hasTag(Integer movieId, String tagName) {
        if (movieId == null || tagName == null || tagName.isEmpty()) return false;
        ensureConnection();
        try {
            if (movieExists(movieId)) return false;

            Integer tagId = findTagId(tagName);
            if (tagId == null) return false;

            return linkExists(movieId, tagId);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public List<String> getTagsForMovie(Integer movieId) {
        List<String> out = new ArrayList<>();
        if (movieId == null) return out;
        ensureConnection();

        String sql =
                "SELECT t.Naziv " +
                        "FROM FilmTag ft JOIN Tag t ON t.TagID = ft.TagID " +
                        "WHERE ft.FilmID = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException ignored) {}
        return out;
    }

    @Override
    public List<Integer> getMovieIdsByTag(String tagName) {
        List<Integer> out = new ArrayList<>();
        if (tagName == null || tagName.isEmpty()) return out;
        ensureConnection();

        String sql =
                "SELECT ft.FilmID " +
                        "FROM Tag t JOIN FilmTag ft ON ft.TagID = t.TagID " +
                        "WHERE t.Naziv = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tagName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException ignored) {}
        return out;
    }

    @Override
    public List<String> getAllTags() {
        List<String> out = new ArrayList<>();
        ensureConnection();

        String sql =
                "SELECT DISTINCT t.Naziv " +
                        "FROM FilmTag ft JOIN Tag t ON t.TagID = ft.TagID";

        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException ignored) {}
        return out;
    }

    void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // Ignore
        }
    }

}
