package com.bignerdranch.android.photogallery;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.util.LruCache;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Chris on 3/25/2017.
 */

public class PhotoGalleryFragment extends Fragment {

    private static final String TAG = "PhotoGalleryFragment";

    private RecyclerView mPhotoRecyclerView;
    private List<GalleryItem> mItems = new ArrayList<>();
    private ThumbnailDownloader<PhotoHolder> mThumbnailDownloader;

    private int mPageNumber = 1;
    private int mNumColumns = 3;

    // Cache instance
    private LruCache<String, Bitmap> mImageCache;

    public static PhotoGalleryFragment newInstance() {
        return new PhotoGalleryFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);

        new FetchItemsTask().execute();

        // Set up handlers for receiving thumbnails from ThumbnailDownloader thread
        Handler responseHandler = new Handler();
        mThumbnailDownloader = new ThumbnailDownloader<>(responseHandler);

        mThumbnailDownloader.setThumbnailDownloadListener(new ThumbnailDownloader.ThumbnailDownloadListener<PhotoHolder>() {
            @Override
            public void onThumbnailDownloaded(PhotoHolder photoHolder, Bitmap thumbnail, String url) {
                Drawable drawable = new BitmapDrawable(getResources(), thumbnail);

                addBitmapToMemoryCache(url, thumbnail);
                photoHolder.bindDrawable(drawable);
            }
        });
        mThumbnailDownloader.start();
        mThumbnailDownloader.getLooper();

        // Initialize cache

        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);

        // Use 1/8th of the available memory for this memory cache.
        final int cacheSize = maxMemory / 8;

        mImageCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.getByteCount() / 1024;
            }
        };
        Log.i(TAG, "Background thread started");
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

        mPhotoRecyclerView = (RecyclerView) v.findViewById(R.id.photo_recycler_view);
        mPhotoRecyclerView.setLayoutManager(new GridLayoutManager(getActivity(), mNumColumns));
        mPhotoRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                int totalItems = lm.getItemCount();
                int lastVisibleItem = lm.findLastVisibleItemPosition();

                if ((lastVisibleItem + 10) >= totalItems && mPageNumber < 10) {
                    // Call api and append items (Temporarily replace)
                    mPageNumber++;
                    new FetchItemsTask().execute();
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

    // Add photo into LruCache if it isn't already there
    public void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mImageCache.put(key, bitmap);
        }
    }

    public Bitmap getBitmapFromMemCache(String key) {
        return mImageCache.get(key);
    }

    private void setupAdapter() {
        if (isAdded()) {
            mPhotoRecyclerView.setAdapter(new PhotoAdapter(mItems));
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

            if (getBitmapFromMemCache(galleryItem.getUrl()) != null) {
                currentImage = new BitmapDrawable(getResources(), getBitmapFromMemCache(galleryItem.getUrl()));
                Log.i(TAG, "Found in cache, no need to ");
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

        @Override
        protected List<GalleryItem> doInBackground(Void... params) {

            return new FlickrFetchr().fetchItems(mPageNumber);
        }

        @Override
        protected void onPostExecute(List<GalleryItem> galleryItems) {
            if (mItems.size() == 0) {
                mItems = galleryItems;
                setupAdapter();
            } else {
                int oldSize = mItems.size();
                mItems.addAll(galleryItems);
                mPhotoRecyclerView.getAdapter().notifyItemRangeInserted(oldSize, galleryItems.size());
            }
        }
    }
}
