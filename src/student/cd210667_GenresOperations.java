package src.student;

import rs.ac.bg.etf.sab.operations.GenresOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_GenresOperations implements GenresOperations {

    private Connection connection;

    public cd210667_GenresOperations() {
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

    @Override
    public Integer addGenre(String name) {
        if (name == null || name.isBlank()) return null;
        ensureConnection();

        // postoji li već?
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ZanrID FROM dbo.Zanr WHERE Naziv = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("addGenre: check failed", e);
        }

        // insert
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO dbo.Zanr (Naziv) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("addGenre: insert failed", e);
        }
    }

    @Override
    public Integer updateGenre(Integer genreId, String newName) {
        if (genreId == null || newName == null || newName.isBlank()) return null;
        ensureConnection();

        // postoji li id?
        boolean exists = false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Zanr WHERE ZanrID = ?")) {
            ps.setInt(1, genreId);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("updateGenre: existence check failed", e);
        }
        if (!exists) return null;

        // da li bi nastao duplikat imena?
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Zanr WHERE Naziv = ? AND ZanrID <> ?")) {
            ps.setString(1, newName);
            ps.setInt(2, genreId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("updateGenre: duplicate check failed", e);
        }

        // update
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE dbo.Zanr SET Naziv = ? WHERE ZanrID = ?")) {
            ps.setString(1, newName);
            ps.setInt(2, genreId);
            int affected = ps.executeUpdate();
            return affected == 1 ? genreId : null;
        } catch (SQLException e) {
            throw new RuntimeException("updateGenre: update failed", e);
        }
    }

    @Override
    public Integer removeGenre(Integer genreId) {
        if (genreId == null) return null;
        ensureConnection();

        // postoji li id?
        boolean exists = false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Zanr WHERE ZanrID = ?")) {
            ps.setInt(1, genreId);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("removeGenre: existence check failed", e);
        }
        if (!exists) return null;

        // transakcija: prvo veze, pa žanr
        try {
            boolean oldAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM dbo.FilmZanr WHERE ZanrID = ?")) {
                ps.setInt(1, genreId);
                ps.executeUpdate();
            }

            int affected;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM dbo.Zanr WHERE ZanrID = ?")) {
                ps.setInt(1, genreId);
                affected = ps.executeUpdate();
            }

            connection.commit();
            connection.setAutoCommit(oldAuto);
            return affected == 1 ? genreId : null;

        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("removeGenre failed", e);
        }
    }

    @Override
    public boolean doesGenreExist(String name) {
        if (name == null || name.isBlank()) return false;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Zanr WHERE Naziv = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("doesGenreExist failed", e);
        }
    }

    @Override
    public Integer getGenreId(String name) {
        if (name == null || name.isBlank()) return null;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ZanrID FROM dbo.Zanr WHERE Naziv = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getGenreId failed", e);
        }
    }

    @Override
    public List<Integer> getAllGenreIds() {
        ensureConnection();
        List<Integer> out = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ZanrID FROM dbo.Zanr ORDER BY ZanrID ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException("getAllGenreIds failed", e);
        }
        return out;
    }
}
