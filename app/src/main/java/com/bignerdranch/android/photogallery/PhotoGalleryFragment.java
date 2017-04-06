package com.bignerdranch.android.photogallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.util.LruCache;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.ProgressBar;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Chris on 3/25/2017.
 */

public class PhotoGalleryFragment extends Fragment {

    private static final String TAG = "PhotoGalleryFragment";

    private RecyclerView mPhotoRecyclerView;
    private ProgressBar mProgressBar;
    private List<GalleryItem> mItems = new ArrayList<>();
    private ThumbnailDownloader<PhotoHolder> mThumbnailDownloader;
    private ThumbnailPreloader<Integer> mThumbnailPreloader;

    private int mPageNumber = 1;
    private int mNumColumns = 3;
    private boolean mNewQuery = true;

    // Cache instance
    private LruCache<String, Bitmap> mImageCache;

    public static PhotoGalleryFragment newInstance() {
        return new PhotoGalleryFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        setHasOptionsMenu(true);

        updateItems();

        // Set up handlers for receiving thumbnails from ThumbnailDownloader thread
        Handler responseHandler = new Handler();
        mThumbnailDownloader = new ThumbnailDownloader<>(responseHandler);
        mThumbnailPreloader = new ThumbnailPreloader<Integer>(responseHandler);

        // Place image in cache and set photoHolder image
        mThumbnailDownloader.setThumbnailDownloadListener(new ThumbnailDownloader.ThumbnailDownloadListener<PhotoHolder>() {
            @Override
            public void onThumbnailDownloaded(PhotoHolder photoHolder, Bitmap thumbnail, String url) {
                Drawable drawable = new BitmapDrawable(getResources(), thumbnail);

                PhotoCache photoCache = PhotoCache.get(getContext());
                photoCache.addBitmapToMemoryCache(url, thumbnail);
                photoHolder.bindDrawable(drawable);
            }
        });

        // Store preloaded image in cache
        mThumbnailPreloader.setThumbnailDownloadListener(new ThumbnailPreloader.ThumbnailDownloadListener<Integer>() {
            @Override
            public void onThumbnailDownloaded(Integer target, Bitmap thumbnail, String url) {
                // Only store in cache since we are preloading
                PhotoCache photoCache = PhotoCache.get(getContext());
                photoCache.addBitmapToMemoryCache(url, thumbnail);
            }
        });

        mThumbnailDownloader.start();
        mThumbnailDownloader.getLooper();
        Log.i(TAG, "Download Background thread started");

        mThumbnailPreloader.setPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
        mThumbnailPreloader.start();
        mThumbnailDownloader.getLooper();
        Log.i(TAG, "Preloader background thread started");


        // TODO: 4/5/2017 add preloading
        // load first images on setup
        // load next ten as well, into cache
        // on scroll check that the first visible index - 10 >= 0
        //// if it is, add the previous 10 images to the cache
        // on scroll check that the last visible index + 10 < gallery array length
        //// if it is, loop through and add each to download and then go in the cache
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_photo_gallery, menu);

        MenuItem searchItem = menu.findItem(R.id.menu_item_search);
        final SearchView searchView = (SearchView) searchItem.getActionView();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                Log.d(TAG, "QueryTextSubmit: " + query);
                QueryPreferences.setStoredQuery(getActivity(), query);
                hideKeyboard();
                mProgressBar.setVisibility(View.VISIBLE);
                mNewQuery = true;
                mItems = new ArrayList<GalleryItem>();
                setupAdapter();
                updateItems();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                Log.d(TAG, "QueryTextChange: " + newText);
                return false;
            }
        });
    }

    private void hideKeyboard() {
        // https://stackoverflow.com/questions/3400028/close-virtual-keyboard-on-button-press
        // Thanks to users: Mazzy and Peter Ajtai
        InputMethodManager inputManager = (InputMethodManager) getActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE);

         View currentFocus = (View) getActivity().getCurrentFocus();
        // Make sure no error is thrown if keyboard is already closed
        IBinder windowToken = currentFocus == null ? null : currentFocus.getWindowToken();

        inputManager.hideSoftInputFromWindow(windowToken, InputMethodManager.HIDE_NOT_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_clear:
                QueryPreferences.setStoredQuery(getActivity(), null);
                updateItems();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateItems() {
        String query = QueryPreferences.getStoredQuery(getActivity());

        new FetchItemsTask(query).execute();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mThumbnailDownloader.clearQueue();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // destroy background thread or it will continue indefinitely
        mThumbnailDownloader.quit();
        Log.i(TAG, "Background thread destroyed");
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_photo_gallery, container, false);

        mProgressBar = (ProgressBar) v.findViewById(R.id.circle_loader);
        mPhotoRecyclerView = (RecyclerView) v.findViewById(R.id.photo_recycler_view);
        mPhotoRecyclerView.setLayoutManager(new GridLayoutManager(getActivity(), mNumColumns));
        mPhotoRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                int totalItems = lm.getItemCount();
                int lastVisibleItem = lm.findLastVisibleItemPosition();
                preloadPhotos();

                if ((lastVisibleItem + 10) >= totalItems && mPageNumber < 10) {
                    // Call api and append items (Temporarily replace)
                    mPageNumber++;
                    updateItems();
                }
            }
        });

        // Layout listener for updating column count based on width at runtime
        mPhotoRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                GridLayoutManager manager = (GridLayoutManager) mPhotoRecyclerView.getLayoutManager();
                float currentWidth = manager.getWidth();
                mNumColumns = (int) currentWidth / 300;
                manager.setSpanCount(mNumColumns);
            }
        });

        setupAdapter();
        return v;
    }

    private void setupAdapter() {

        if (isAdded()) {
            mPhotoRecyclerView.setAdapter(new PhotoAdapter(mItems));
            preloadPhotos();
        }
    }

    private void preloadPhotos() {
        GridLayoutManager lm = (GridLayoutManager) mPhotoRecyclerView.getLayoutManager();
        int firstVisiblePosition = lm.findFirstVisibleItemPosition();
        int lastVisiblePosition = lm.findLastVisibleItemPosition();
        PhotoCache photoCache = PhotoCache.get(getContext());

        if (firstVisiblePosition - 10 >= 0) {
            // load previous 10 images into cache
            for (int position = firstVisiblePosition - 10; position < firstVisiblePosition; position++) {
                String url = mItems.get(position).getUrl();

                if (photoCache.getBitmapFromMemCache(url) != null) {
                    // No need to re-download this
                    continue;
                }
                mThumbnailPreloader.queueThumbnail(position, url);
            }

            // load next 10 images
            if (lastVisiblePosition + 10 <= mItems.size()) {
                for (int position = lastVisiblePosition + 1; position < lastVisiblePosition + 10; position++) {
                    String url = mItems.get(position).getUrl();

                    if (photoCache.getBitmapFromMemCache(url) != null) {
                        // no need to re-download this
                        Log.i(TAG, "Already cached");
                        continue;
                    }

                    mThumbnailPreloader.queueThumbnail(position, url);
                }
            }

        }
    }

    private class PhotoHolder extends RecyclerView.ViewHolder {

        private ImageView mItemImageView;

        public PhotoHolder(View itemView) {
            super(itemView);

            mItemImageView = (ImageView) itemView.findViewById(R.id.item_image_view);
        }

        public void bindDrawable(Drawable drawable) {

            mItemImageView.setImageDrawable(drawable);
        }
    }

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoHolder> {

        private List<GalleryItem> mGalleryItems;

        public PhotoAdapter(List<GalleryItem> galleryItems) {
            mGalleryItems = galleryItems;
        }

        @Override
        public PhotoHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(getActivity());
            View v = inflater.inflate(R.layout.list_item_gallery, parent, false);

            return new PhotoHolder(v);
        }

        @Override
        public void onBindViewHolder(PhotoHolder holder, int position) {
            GalleryItem galleryItem = mGalleryItems.get(position);
            // current image
            Drawable currentImage = getResources().getDrawable(R.drawable.bill_up_close);

            PhotoCache photoCache = PhotoCache.get(getContext());

            if (photoCache.getBitmapFromMemCache(galleryItem.getUrl()) != null) {
                currentImage = new BitmapDrawable(getResources(), photoCache.getBitmapFromMemCache(galleryItem.getUrl()));
                Log.i(TAG, "Found in cache, no need to download image");
            } else {
                mThumbnailDownloader.queueThumbnail(holder, galleryItem.getUrl());
            }

            holder.bindDrawable(currentImage);
        }

        @Override
        public int getItemCount() {
            return mGalleryItems.size();
        }

    }

    private class FetchItemsTask extends AsyncTask<Void, Void, List<GalleryItem>> {

        private String mQuery;

        public FetchItemsTask(String query) {
            mQuery = query;
        }

        @Override
        protected List<GalleryItem> doInBackground(Void... params) {

            if (mQuery == null) {
                return new FlickrFetchr()
                        .fetchRecentPhotos(mPageNumber);
            } else {
                return new FlickrFetchr()
                        .searchPhotos(mQuery, mPageNumber);
            }
        }

        @Override
        protected void onPostExecute(List<GalleryItem> galleryItems) {
            if (mProgressBar.getVisibility() == View.VISIBLE) {
                mProgressBar.setVisibility(View.GONE);
            }

            if (mItems.size() == 0 || mNewQuery) {
                mItems = galleryItems;
                mNewQuery = false;
                setupAdapter();
            } else {
                int oldSize = mItems.size();
                mItems.addAll(galleryItems);
                mPhotoRecyclerView.getAdapter().notifyItemRangeInserted(oldSize, galleryItems.size());
            }
        }
    }
}
