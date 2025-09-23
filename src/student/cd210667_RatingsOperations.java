package src.student;

import rs.ac.bg.etf.sab.operations.RatingsOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_RatingsOperations implements RatingsOperations {

    private Connection connection;

    public cd210667_RatingsOperations() {
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
    public boolean addRating(Integer userId, Integer movieId, Integer value) {
        if (userId == null || movieId == null || value == null) return false;
        // (opciono) striktno pravilo 1..10
        if (value < 1 || value > 10) return false;
        ensureConnection();

        // postoji li već ocena tog korisnika za taj film?
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM dbo.Ocena WHERE KorisnikID = ? AND FilmID = ?")) {
            ps.setInt(1, userId);
            ps.setInt(2, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return false; // duplikat nije dozvoljen
            }
        } catch (SQLException e) {
            throw new RuntimeException("addRating: existence check failed", e);
        }

        // upis
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO dbo.Ocena (KorisnikID, FilmID, Ocena, Vreme) VALUES (?,?,?, SYSDATETIME())")) {
            ps.setInt(1, userId);
            ps.setInt(2, movieId);
            ps.setInt(3, value);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {

            return false;
        }
    }

    @Override
    public boolean updateRating(Integer userId, Integer movieId, Integer value) {
        if (userId == null || movieId == null || value == null) return false;
        if (value < 1 || value > 10) return false;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE dbo.Ocena SET Ocena = ?, Vreme = SYSDATETIME() " +
                        "WHERE KorisnikID = ? AND FilmID = ?")) {
            ps.setInt(1, value);
            ps.setInt(2, userId);
            ps.setInt(3, movieId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean removeRating(Integer userId, Integer movieId) {
        if (userId == null || movieId == null) return false;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM dbo.Ocena WHERE KorisnikID = ? AND FilmID = ?")) {
            ps.setInt(1, userId);
            ps.setInt(2, movieId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("removeRating failed", e);
        }
    }

    @Override
    public Integer getRating(Integer userId, Integer movieId) {
        if (userId == null || movieId == null) return null;
        ensureConnection();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Ocena FROM dbo.Ocena WHERE KorisnikID = ? AND FilmID = ?")) {
            ps.setInt(1, userId);
            ps.setInt(2, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getRating failed", e);
        }
    }

    @Override
    public List<Integer> getRatedMoviesByUser(Integer userId) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (userId == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT FilmID FROM dbo.Ocena WHERE KorisnikID = ? ORDER BY FilmID ASC")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getRatedMoviesByUser failed", e);
        }
        return out;
    }

    @Override
    public List<Integer> getUsersWhoRatedMovie(Integer movieId) {
        ensureConnection();
        List<Integer> out = new ArrayList<>();
        if (movieId == null) return out;

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT KorisnikID FROM dbo.Ocena WHERE FilmID = ? ORDER BY KorisnikID ASC")) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getUsersWhoRatedMovie failed", e);
        }
        return out;
    }
}
