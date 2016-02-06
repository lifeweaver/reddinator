package au.com.wallaceit.reddinator.tasks;
/*
 * Copyright 2013 Michael Boyde Wallace (http://wallaceit.com.au)
 * This file is part of Reddinator.
 *
 * Reddinator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Reddinator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Reddinator (COPYING). If not, see <http://www.gnu.org/licenses/>.
 *
 * Created by michael on 6/02/16.
 */

import android.os.AsyncTask;
import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.core.RedditData;

public class VoteTask extends AsyncTask<String, Integer, Boolean> {
    private Reddinator global;
    private String redditId;
    private int direction;
    private RedditData.RedditApiException exception = null;
    private Callback voteCallback = null;

    public interface Callback {
        void onVoteComplete(boolean result, RedditData.RedditApiException exception, String redditId, int direction);
    }

    public VoteTask(Reddinator global, Callback voteCallback, String redditId, int direction) {
        this.global = global;
        this.voteCallback = voteCallback;
        this.direction = direction;
        this.redditId = redditId;
    }

    @Override
    protected Boolean doInBackground(String... strings) {
        // Do the vote
        try {
            return global.mRedditData.vote(redditId, direction);
        } catch (RedditData.RedditApiException e) {
            e.printStackTrace();
            exception = e;
            return false;
        }
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (voteCallback!=null)
            voteCallback.onVoteComplete(result, exception, redditId, direction);
    }
}