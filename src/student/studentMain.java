package src.student;

import rs.ac.bg.etf.sab.operations.*;
import rs.ac.bg.etf.sab.tests.*;

public class studentMain {
    static void main(String[] args) throws Exception {

        GeneralOperations generalOperations = new cd210667_GeneralOperations();
        GenresOperations genresOperations = new cd210667_GenresOperations();
        MoviesOperations moviesOperations = new cd210667_MoviesOperations();
        RatingsOperations ratingsOperation = new cd210667_RatingsOperations();
        TagsOperations tagsOperations = new cd210667_TagsOperations();
        UsersOperations usersOperations = new cd210667_UsersOperations();
        WatchlistsOperations watchlistsOperations = new cd210667_WatchlistsOperations();

        generalOperations.eraseAll();

        TestHandler.createInstance(
                genresOperations,
                moviesOperations,
                ratingsOperation,
                tagsOperations,
                usersOperations,
                watchlistsOperations,
                generalOperations);
        TestRunner.runTests();
        
        
    }


}

