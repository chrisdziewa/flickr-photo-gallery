package com.bignerdranch.android.photogallery;

import android.content.Context;
import android.preference.PreferenceManager;

import static android.preference.PreferenceManager.getDefaultSharedPreferences;

/**
 * Created by Chris on 4/6/2017.
 */

public class QueryPreferences {

    private static final String PREF_SEARCH_QUERY = "searchQuery";

    public static String getStoredQuery(Context context) {
        return getDefaultSharedPreferences(context)
                .getString(PREF_SEARCH_QUERY, null);
    }

    public static void setStoredQuery(Context context, String query) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_SEARCH_QUERY, query)
                .apply();
    }
}
