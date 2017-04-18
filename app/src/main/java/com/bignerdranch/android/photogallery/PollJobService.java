package com.bignerdranch.android.photogallery;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.util.List;

import static com.bignerdranch.android.photogallery.PollService.PERM_PRIVATE;


/**
 * Created by Chris on 4/14/2017.
 */


@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class PollJobService extends JobService {
    private static final String TAG = "PollJobService";

    // Broadcast
    public static final String ACTION_SHOW_NOTIFICATION = "com.bignerdranch.android.photogallery.SHOW_NOTIFICATION";

    // For Lollipop and above background job service
    public static final int JOB_ID = 1;
    private PollTask mCurrentTask;

    @Override
    public boolean onStartJob(JobParameters params) {
        // Async task to fetch items from Flickr api
        mCurrentTask = new PollTask();
        mCurrentTask.execute(params);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mCurrentTask != null) {
            mCurrentTask.cancel(true);
        }
        return true;
    }

    public static boolean isJobScheduled(Context context, int id) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        boolean hasBeenScheduled = false;
        for (JobInfo jobInfo : scheduler.getAllPendingJobs()) {
            if (jobInfo.getId() == id) {
                hasBeenScheduled = true;
            }
        }

        return hasBeenScheduled;
    }

    public static void scheduleNewJob(Context context, boolean shouldStartJob) {
        final JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);

        if (shouldStartJob) {

            JobInfo.Builder jobInfoBuilder =
                    new JobInfo.Builder(JOB_ID, new ComponentName(context, PollJobService.class))
                            .setPersisted(true)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.i(TAG, "Android N");
                jobInfoBuilder.setPeriodic(1000 * 60 * 15, 1000 * 60 * 5);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Log.i(TAG, "Android Lollipop");
                jobInfoBuilder.setPeriodic(1000 * 60);
            } else {
                return;
            }
            JobInfo jobInfo = jobInfoBuilder.build();
            scheduler.schedule(jobInfo);
            Log.i(TAG, "scheduleNewJob: started new");
        } else {
            Log.i(TAG, "scheduleNewJob: canceled old");
            for (JobInfo jobInfo : scheduler.getAllPendingJobs()) {
                if (jobInfo.getId() == JOB_ID) {
                    scheduler.cancel(JOB_ID);
                }
            }
        }

        QueryPreferences.setAlarmOn(context, shouldStartJob);
    }

    private class PollTask extends AsyncTask<JobParameters, Void, List<GalleryItem>> {

        @Override
        protected List<GalleryItem> doInBackground(JobParameters... params) {
            JobParameters jobParameters = params[0];

            Log.i(TAG, "doInBackground: called");
            // call flicker and send notification
            Log.i(TAG, "Polling from JobService!!");
            String query = QueryPreferences.getStoredQuery(getApplicationContext());

            List<GalleryItem> items;

            if (query == null) {
                items = new FlickrFetchr().fetchRecentPhotos(0);
            } else {
                items = new FlickrFetchr().searchPhotos(query, 0);
            }
            jobFinished(jobParameters, false);
            return items;
        }

        @Override
        protected void onPostExecute(List<GalleryItem> galleryItems) {
            String lastResultId = QueryPreferences.getLastResultId(getApplicationContext());
            Context context = getApplicationContext();

            if (galleryItems.size() == 0) {
                return;
            }

            String resultId = galleryItems.get(0).getId();
            if (resultId.equals(lastResultId)) {
                Log.i(TAG, "Got an old result: " + resultId);
            } else {
                Log.i(TAG, "Got a new result: " + resultId);

                Resources resources = getResources();
                Intent i = PhotoGalleryActivity.newIntent(context);
                PendingIntent pi = PendingIntent.getActivity(context, 0, i, 0);

                Notification notification = new NotificationCompat.Builder(context)
                        .setTicker(resources.getString(R.string.new_pictures_text))
                        .setSmallIcon(android.R.drawable.ic_menu_report_image)
                        .setContentTitle(resources.getString(R.string.new_pictures_title))
                        .setContentText(resources.getString(R.string.new_pictures_text))
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                showBackgroundNotification(0, notification);

            }
            QueryPreferences.setLastResultId(context, resultId);
        }
    }

    private void showBackgroundNotification(int requestCode, Notification notification) {
        Intent i = new Intent(ACTION_SHOW_NOTIFICATION);
        i.putExtra(PollService.REQUEST_CODE, requestCode);
        i.putExtra(PollService.NOTIFICATION, notification);
        sendOrderedBroadcast(i, PERM_PRIVATE, null, null, Activity.RESULT_OK, null, null);
    }

}
