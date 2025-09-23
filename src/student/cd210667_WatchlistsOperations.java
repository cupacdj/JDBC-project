package src.student;

import rs.ac.bg.etf.sab.operations.WatchlistsOperations;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class cd210667_WatchlistsOperations implements WatchlistsOperations {

    private static final String DEFAULT_WATCHLIST_NAME = "default";

    private Connection connection;

    public cd210667_WatchlistsOperations() {
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

    /* =======================
       Helperi
    ======================= */

    private boolean userExists(int userId) throws SQLException {
        String sql = "SELECT 1 FROM Korisnik WHERE KorisnikID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private boolean movieExists(int movieId) throws SQLException {
        String sql = "SELECT 1 FROM Film WHERE FilmID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    /** Uzima ID podrazumevane watchliste korisnika ili je kreira. */
    private Integer getOrCreateDefaultWatchlistId(int userId) throws SQLException {
        // 1) pokušaj da nađeš postojeću
        String sel = "SELECT ListaGledanjaID FROM ListaGledanja WHERE KorisnikID = ? AND Naziv = ?";
        try (PreparedStatement ps = connection.prepareStatement(sel)) {
            ps.setInt(1, userId);
            ps.setString(2, DEFAULT_WATCHLIST_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        // 2) kreiraj novu
        String ins = "INSERT INTO ListaGledanja (KorisnikID, Naziv) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, DEFAULT_WATCHLIST_NAME);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return null;
    }

    private Integer getDefaultWatchlistIdIfExists(int userId) throws SQLException {
        String sel = "SELECT ListaGledanjaID FROM ListaGledanja WHERE KorisnikID = ? AND Naziv = ?";
        try (PreparedStatement ps = connection.prepareStatement(sel)) {
            ps.setInt(1, userId);
            ps.setString(2, DEFAULT_WATCHLIST_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private boolean linkExists(int watchlistId, int movieId) throws SQLException {
        String sql = "SELECT 1 FROM ListaGledanjaStavka WHERE ListaGledanjaID = ? AND FilmID = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, watchlistId);
            ps.setInt(2, movieId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /* =======================
       Implementacije interfejsa
    ======================= */

    @Override
    public boolean addMovieToWatchlist(Integer userId, Integer movieId) {
        if (userId == null || movieId == null) return false;
        ensureConnection();
        try {
            if (userExists(userId) || movieExists(movieId)) return false;

            Integer wlId = getOrCreateDefaultWatchlistId(userId);
            if (wlId == null) return false;

            if (linkExists(wlId, movieId)) return false; // duplikat

            String ins = "INSERT INTO ListaGledanjaStavka (ListaGledanjaID, FilmID) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(ins)) {
                ps.setInt(1, wlId);
                ps.setInt(2, movieId);
                ps.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean removeMovieFromWatchlist(Integer userId, Integer movieId) {
        if (userId == null || movieId == null) return false;
        ensureConnection();
        try {
            // Ako korisnik ili film ne postoje, nema šta da brišemo
            if (userExists(userId) || movieExists(movieId)) return false;

            Integer wlId = getDefaultWatchlistIdIfExists(userId);
            if (wlId == null) return false;

            String del = "DELETE FROM ListaGledanjaStavka WHERE ListaGledanjaID = ? AND FilmID = ?";
            try (PreparedStatement ps = connection.prepareStatement(del)) {
                ps.setInt(1, wlId);
                ps.setInt(2, movieId);
                int rows = ps.executeUpdate();
                return rows > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean isMovieInWatchlist(Integer userId, Integer movieId) {
        if (userId == null || movieId == null) return false;
        ensureConnection();
        try {
            if (userExists(userId) || movieExists(movieId)) return false;

            Integer wlId = getDefaultWatchlistIdIfExists(userId);
            if (wlId == null) return false;

            return linkExists(wlId, movieId);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public List<Integer> getMoviesInWatchlist(Integer userId) {
        List<Integer> out = new ArrayList<>();
        if (userId == null) return out;
        ensureConnection();
        try {
            Integer wlId = getDefaultWatchlistIdIfExists(userId);
            if (wlId == null) return out;

            String sql = "SELECT FilmID FROM ListaGledanjaStavka WHERE ListaGledanjaID = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, wlId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getInt(1));
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    @Override
    public List<Integer> getUsersWithMovieInWatchlist(Integer movieId) {
        List<Integer> out = new ArrayList<>();
        if (movieId == null) return out;
        ensureConnection();
        try {
            // Uzmemo sve korisnike koji imaju film u BILO KOJOJ njihovoj listi (testovi će i dalje raditi
            // jer mi koristimo samo "default" listu).
            String sql =
                    "SELECT DISTINCT lg.KorisnikID " +
                            "FROM ListaGledanjaStavka lgs " +
                            "JOIN ListaGledanja lg ON lg.ListaGledanjaID = lgs.ListaGledanjaID " +
                            "WHERE lgs.FilmID = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getInt(1));
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }
}
