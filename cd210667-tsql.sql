USE [Filmovi]
GO

CREATE TRIGGER [dbo].[TR_BLOCK_EXTREME_GRADES]
    ON [dbo].[Ocena]
    AFTER INSERT, UPDATE
    AS
BEGIN
    SET NOCOUNT ON;

    DECLARE
        @KorisnikID INT,
        @FilmID     INT,
        @OcenaNew   TINYINT;

    DECLARE cur CURSOR LOCAL FAST_FORWARD FOR
        SELECT i.KorisnikID, i.FilmID, i.Ocena
        FROM inserted i;

    OPEN cur;
    FETCH NEXT FROM cur INTO @KorisnikID, @FilmID, @OcenaNew;

    WHILE @@FETCH_STATUS = 0
        BEGIN

            IF (@OcenaNew NOT IN (1,10))
                BEGIN
                    FETCH NEXT FROM cur INTO @KorisnikID, @FilmID, @OcenaNew;
                    CONTINUE;
                END

            /* Da li film ima bar jedan žanr? */
            IF NOT EXISTS (SELECT 1 FROM dbo.FilmZanr WHERE FilmID = @FilmID)
                BEGIN
                    FETCH NEXT FROM cur INTO @KorisnikID, @FilmID, @OcenaNew;
                    CONTINUE;
                END

            DECLARE @extPrev INT, @neuPrev INT;

            WITH TargetGenres AS (
                SELECT DISTINCT fz.ZanrID
                FROM dbo.FilmZanr fz
                WHERE fz.FilmID = @FilmID
            ),
                 RelatedFilms AS (
                     SELECT DISTINCT fz2.FilmID
                     FROM dbo.FilmZanr fz2
                     WHERE fz2.ZanrID IN (SELECT ZanrID FROM TargetGenres)
                 )
            SELECT
                @extPrev = ISNULL(SUM(CASE WHEN o.Ocena IN (1,10) THEN 1 ELSE 0 END), 0),
                @neuPrev = ISNULL(SUM(CASE WHEN o.Ocena IN (6,7,8) THEN 1 ELSE 0 END), 0)
            FROM dbo.Ocena o
                     JOIN (
                SELECT DISTINCT fz2.FilmID
                FROM dbo.FilmZanr fz2
                WHERE fz2.ZanrID IN (
                    SELECT DISTINCT fz.ZanrID
                    FROM dbo.FilmZanr fz
                    WHERE fz.FilmID = @FilmID
                )
            ) RF ON RF.FilmID = o.FilmID
            WHERE o.KorisnikID = @KorisnikID
              AND NOT (o.KorisnikID = @KorisnikID AND o.FilmID = @FilmID); -- isključi TEKUĆU ocenu

            IF (@extPrev > 3 AND @neuPrev < 3)
                BEGIN
                    ;THROW 51031,
                        N'Blokirano: imate više od 3 ekstremne (1/10) i manje od 3 neutralne (6/7/8) ocene u žanru/žanrovima ovog filma.
                        Dajte neutralne ocene ili izmenite prethodne ekstremne u neekstremne.', 1;

                    CLOSE cur; DEALLOCATE cur;
                    RETURN;
                END

            FETCH NEXT FROM cur INTO @KorisnikID, @FilmID, @OcenaNew;
        END

    CLOSE cur;
    DEALLOCATE cur;
END;


GO
USE [Filmovi]
DROP PROCEDURE IF EXISTS dbo.SP_REWARD_USER_ON_RATING
GO
CREATE PROCEDURE [dbo].[SP_REWARD_USER_ON_RATING]
    @KorisnikID INT,
    @FilmID     INT
AS
BEGIN
    SET NOCOUNT ON;

    --Mora postojati ocena korisnika za dati film
    IF NOT EXISTS (
        SELECT 1
        FROM dbo.Ocena
        WHERE KorisnikID = @KorisnikID AND FilmID = @FilmID
    )
        RETURN;

    -- Korisnik može dobiti nagradu tek od 10. ocenjenog filma
    DECLARE @UserRatings INT;
    SELECT @UserRatings = COUNT(*)
    FROM dbo.Ocena
    WHERE KorisnikID = @KorisnikID;

    IF (@UserRatings < 10)
        RETURN;

    --Globalni prosek filma bez njegove ocene: mora da postoji bar jedna tuđa ocena i prosek < 6
    DECLARE @CntOthers INT, @AvgOthers DECIMAL(5,2);
    SELECT
        @CntOthers = COUNT(*),
        @AvgOthers = AVG(CAST(Ocena AS DECIMAL(5,2)))
    FROM dbo.Ocena
    WHERE FilmID = @FilmID
      AND KorisnikID <> @KorisnikID;

    IF (@CntOthers = 0 OR @AvgOthers >= 6)
        RETURN;

    -- Film pripada bar jednom korisnikovom "omiljenom" žanru (prosek korisnika po žanru >= 8)
    IF NOT EXISTS (
        SELECT 1
        FROM (
                 SELECT fz.ZanrID, AVG(CAST(o.Ocena AS DECIMAL(5,2))) AS AvgOcena
                 FROM dbo.Ocena o
                          JOIN dbo.FilmZanr fz ON fz.FilmID = o.FilmID
                 WHERE o.KorisnikID = @KorisnikID
                 GROUP BY fz.ZanrID
             ) AS UGA
                 JOIN dbo.FilmZanr fg ON fg.ZanrID = UGA.ZanrID
        WHERE fg.FilmID = @FilmID
          AND UGA.AvgOcena >= 8
    )
        RETURN;


    INSERT INTO UserRewardLog(KorisnikId, FilmId, Vreme)
    SELECT @KorisnikID, @FilmID, GETDATE()
    WHERE NOT EXISTS (
        SELECT 1
        FROM UserRewardLog
        WHERE KorisnikId = @KorisnikID AND FilmId = @FilmID
    );

    IF (@@ROWCOUNT = 1)
        BEGIN
            UPDATE dbo.Korisnik
            SET BrojNagrada = BrojNagrada + 1
            WHERE KorisnikID = @KorisnikID;
        END
END
GO

USE [Filmovi]
GO
DROP TRIGGER IF EXISTS dbo.TR_REWARD_ON_RATING;
GO
CREATE TRIGGER dbo.TR_REWARD_ON_RATING
    ON dbo.Ocena
    AFTER INSERT, UPDATE
    AS
BEGIN
    SET NOCOUNT ON;

    -- DISTINCT da ne pozivamo proceduru više puta za isti (korisnik, film)
    DECLARE cur CURSOR LOCAL FAST_FORWARD FOR
        SELECT DISTINCT i.KorisnikID, i.FilmID
        FROM inserted AS i;

    DECLARE @KorisnikID INT, @FilmID INT;

    OPEN cur;
    FETCH NEXT FROM cur INTO @KorisnikID, @FilmID;

    WHILE @@FETCH_STATUS = 0
        BEGIN
            EXEC dbo.SP_REWARD_USER_ON_RATING
                 @KorisnikID = @KorisnikID,
                 @FilmID     = @FilmID;

            FETCH NEXT FROM cur INTO @KorisnikID, @FilmID;
        END

    CLOSE cur; DEALLOCATE cur;
END
GO

