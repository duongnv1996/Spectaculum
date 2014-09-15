/*
 * Copyright (c) 2014 Mario Guggenberger <mario.guggenberger@aau.at>
 *
 * This file is part of ITEC MediaPlayer.
 *
 * ITEC MediaPlayer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ITEC MediaPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ITEC MediaPlayer.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.aau.itec.android.mediaplayer.dash;

import android.content.Context;
import android.media.MediaFormat;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.util.Log;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.TrackBox;
import com.googlecode.mp4parser.MemoryDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Mp4TrackImpl;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.boxes.threegpp26244.SegmentIndexBox;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import at.aau.itec.android.mediaplayer.MediaExtractor;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;

/**
 * Encapsulates DASH data source processing. The Android API's MediaExtractor doesn't support
 * switching between / chaining of multiple data sources, e.g. an initialization segment and a
 * succeeding media data segment. This class takes care of DASH file downloads, merging init data
 * with media file segments, chaining multiple files and switching between them.
 *
 * From outside, it looks like it is processing a single data source, similar to the Android API MediaExtractor.
 *
 * Created by maguggen on 27.08.2014.
 */
class DashMediaExtractor extends MediaExtractor {

    private static final String TAG = DashMediaExtractor.class.getSimpleName();

    private static volatile int sInstanceCount = 0;

    private Context mContext;
    private MPD mMPD;
    private AdaptationLogic mAdaptationLogic;
    private AdaptationSet mAdaptationSet;
    private Representation mRepresentation;
    private boolean mRepresentationSwitched;
    private int mCurrentSegment;
    private List<Integer> mSelectedTracks;
    private OkHttpClient mHttpClient;
    private Map<Representation, ByteString> mInitSegments;
    private Map<Segment, CachedSegment> mFutureCache; // the cache for upcoming segments
    private Map<Segment, Call> mFutureCacheRequests; // requests for upcoming segments
    private SegmentLruCache mUsedCache; // cache for used or in use segments
    private boolean mMp4Mode;
    private DefaultMp4Builder mMp4Builder;
    private long mSegmentPTSOffsetUs;

    public DashMediaExtractor() {
        mHttpClient = new OkHttpClient();
    }

    public final void setDataSource(Context context, MPD mpd, AdaptationLogic adaptationLogic)
            throws IOException {
        try {
            mContext = context;
            mMPD = mpd;
            mAdaptationLogic = adaptationLogic;
            mAdaptationSet = mMPD.getFirstVideoSet();
            mRepresentation = adaptationLogic.initialize(mAdaptationSet);
            mCurrentSegment = -1;
            mSelectedTracks = new ArrayList<Integer>();
            mInitSegments = new HashMap<Representation, ByteString>(mAdaptationSet.representations.size());
            mFutureCache = new HashMap<Segment, CachedSegment>();
            mFutureCacheRequests = new HashMap<Segment, Call>();
            mUsedCache = new SegmentLruCache(100 * 1024 * 1024);
            mMp4Mode = mRepresentation.mimeType.equals("video/mp4");
            if (mMp4Mode) {
                mMp4Builder = new DefaultMp4Builder();
            }
            mSegmentPTSOffsetUs = 0;

            /* If the extractor previously crashed and could not gracefully finish, some old temp files
             * that will never be used again might be around, so just delete all of them and avoid the
             * memory fill up with trash.
             * Only clean at startup of the first instance, else newer ones delete cache files of
             * running ones.
             */
            if (sInstanceCount++ == 0) {
                clearTempDir(mContext);
            }

            initOnWorkerThread(getNextSegment());
        } catch (Exception e) {
            Log.e(TAG, "failed to set data source");
            throw new IOException("failed to set data source", e);
        }
    }

    @Override
    public MediaFormat getTrackFormat(int index) {
        MediaFormat mediaFormat = super.getTrackFormat(index);
        if(mMp4Mode) {
            /* An MP4 that has been converted from a fragmented to an unfragmented container
             * through the isoparser library does only contain the current segment's runtime. To
             * return the total runtime, we take the value from the MPD instead.
             */
            mediaFormat.setLong(MediaFormat.KEY_DURATION, mMPD.mediaPresentationDurationUs);
        }
        return mediaFormat;
    }

    @Override
    public void selectTrack(int index) {
        super.selectTrack(index);
        mSelectedTracks.add(index); // save track selection for later reinitialization
    }

    @Override
    public void unselectTrack(int index) {
        super.unselectTrack(index);
        mSelectedTracks.remove(Integer.valueOf(index));
    }

    @Override
    public int readSampleData(ByteBuffer byteBuf, int offset) {
        int size = super.readSampleData(byteBuf, offset);
        if(size == -1) {
            /* EOS of current segment reached. Check for and read from successive segment if
             * existing, else return the EOS flag. */
            Segment next = getNextSegment();
            if(next != null) {
                /* Since it seems that an extractor cannot be reused by setting another data source,
                 * a new instance needs to be created and used. */
                renewExtractor();

                /* Initialize the new extractor for the next segment */
                initOnWorkerThread(next);
                return super.readSampleData(byteBuf, offset);
            }
        }
        return size;
    }

    @Override
    public long getCachedDuration() {
        return mFutureCache.size() * mRepresentation.segmentDurationUs;
    }

    @Override
    public boolean hasCacheReachedEndOfStream() {
        /* The cache has reached EOS,
         * either if the last segment is in the future cache,
         * or of the last segment is currently played back.
         */
        return mFutureCache.containsKey(mRepresentation.getLastSegment())
                || mCurrentSegment == (mRepresentation.segments.size() - 1);
    }

    @Override
    public long getSampleTime() {
        long sampleTime = super.getSampleTime();
        if(sampleTime == -1) {
            return -1;
        } else {
            //Log.d(TAG, "sampletime = " + (sampleTime + mSegmentPTSOffsetUs))
            return sampleTime + mSegmentPTSOffsetUs;
        }
    }

    @Override
    public void seekTo(long timeUs, int mode) {
        int targetSegmentIndex = Math.min((int)(timeUs / mRepresentation.segmentDurationUs), mRepresentation.segments.size() - 1);
        Log.d(TAG, "seek to " + timeUs + " @ segment " + targetSegmentIndex);
        if(targetSegmentIndex == mCurrentSegment) {
            /* Because the DASH segments do not contain seeking cues, the position in the current
             * segment needs to be reset to the start. Else, seeks are always progressing, never
             * going back in time. */
            super.seekTo(0, mode);
        } else {
            invalidateFutureCache();
            renewExtractor();
            mCurrentSegment = targetSegmentIndex;
            initOnWorkerThread(mRepresentation.segments.get(targetSegmentIndex));
            super.seekTo(timeUs - mSegmentPTSOffsetUs, mode);
        }
    }

    @Override
    public void release() {
        super.release();
        invalidateFutureCache();
        mUsedCache.evictAll();
    }

    @Override
    public boolean hasTrackFormatChanged() {
        if(mRepresentationSwitched) {
            mRepresentationSwitched = false;
            return true;
        }
        return false;
    }

    private void initOnWorkerThread(final Segment segment) {
        /* Avoid NetworkOnMainThreadException by running network request in worker thread
         * but blocking until finished to avoid complicated and in this case unnecessary
         * async handling.
         */
        try {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    init(segment);
                    return null;
                }
            }.execute().get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    private void init(Segment segment) {
        try {
            // Check for segment in caches, and execute blocking download if missing
            // First, check the future cache, without a seek the chance is much higher of finding it there
            CachedSegment cachedSegment = mFutureCache.remove(segment);
            if(cachedSegment == null) {
                // Second, check the already used cache, maybe we had a seek and the segment is already there
                cachedSegment = mUsedCache.get(segment);
                if(cachedSegment == null) {
                    // Third, check if a request is already active
                    Call call = mFutureCacheRequests.get(segment);
                    /* TODO add synchronization to the whole caching code
                     * E.g., a request could have finished between this mFutureCacheRequests call and
                     * the previous mUsedCache call, whose result is missed.
                     */
                    if(call != null) {
                        synchronized (mFutureCache) {
                            try {
                                while((cachedSegment = mFutureCache.remove(segment)) == null) {
                                    Log.d(TAG, "waiting for request to finish " + segment);
                                    mFutureCache.wait();
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    } else {
                        // Fourth, least and worst alternative: blocking download of segment
                        cachedSegment = downloadFile(segment);
                    }
                }
            }

            mUsedCache.put(segment, cachedSegment);
            mSegmentPTSOffsetUs = cachedSegment.ptsOffsetUs;
            setDataSource(cachedSegment.file.getPath());

            // Reselect tracks at reinitialization for a successive segment
            if(!mSelectedTracks.isEmpty()) {
                for(int index : mSelectedTracks) {
                    super.selectTrack(index);
                }
            }

            fillFutureCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Segment getNextSegment() {
        mCurrentSegment++;

        if(mRepresentation.segments.size() <= mCurrentSegment) {
            return null; // EOS, no more segment
        }

        // Switch to the currently best representation
        Representation recommendedRepresentation = mAdaptationLogic.getRecommendedRepresentation(mAdaptationSet);
        if(recommendedRepresentation != mRepresentation) {
            invalidateFutureCache();
            Log.d(TAG, "representation switch: " + mRepresentation + " -> " + recommendedRepresentation);
            mRepresentationSwitched = true;
            mRepresentation = recommendedRepresentation;
        }

        return mRepresentation.segments.get(mCurrentSegment);
    }

    /**
     * Blocking download of a segment.
     */
    private CachedSegment downloadFile(Segment segment) throws IOException {
        // At the first call, download the initialization segments, and reuse them later.
        if(mInitSegments.isEmpty()) {
            for(Representation representation : mAdaptationSet.representations) {
                Request request = buildSegmentRequest(representation.initSegment);
                long startTime = SystemClock.elapsedRealtime();
                Response response = mHttpClient.newCall(request).execute();
                ByteString segmentData = response.body().source().readByteString();
                mInitSegments.put(representation, segmentData);
                mAdaptationLogic.reportSegmentDownload(mAdaptationSet, representation, segment, segmentData.size(), SystemClock.elapsedRealtime() - startTime);
                Log.d(TAG, "init " + representation.initSegment.toString());
            }
        }

        Request request = buildSegmentRequest(segment);
        long startTime = SystemClock.elapsedRealtime();
        Response response = mHttpClient.newCall(request).execute();
        byte[] segmentData = response.body().bytes();
        mAdaptationLogic.reportSegmentDownload(mAdaptationSet, mRepresentation, segment, segmentData.length, SystemClock.elapsedRealtime() - startTime);
        CachedSegment cachedSegment = handleSegment(segmentData, segment);
        Log.d(TAG, "sync dl " + segment.toString() + " -> " + cachedSegment.file.getPath());

        return cachedSegment;
    }

    /**
     * Makes async segment requests to fill the cache up to a certain level.
     */
    private void fillFutureCache() {
        int segmentsToBuffer = (int)Math.ceil((double)mMPD.minBufferTimeUs / mRepresentation.segmentDurationUs);
        for(int i = mCurrentSegment + 1; i < Math.min(mCurrentSegment + 1 + segmentsToBuffer, mRepresentation.segments.size()); i++) {
            Segment segment = mRepresentation.segments.get(i);
            if(!mFutureCache.containsKey(segment) && !mFutureCacheRequests.containsKey(segment)) {
                Request request = buildSegmentRequest(segment);
                Call call = mHttpClient.newCall(request);
                call.enqueue(new SegmentDownloadCallback(segment));
                mFutureCacheRequests.put(segment, call);
            }
        }
    }

    /**
     * Invalidates the cache by cancelling all pending requests and deleting all buffered segments.
     */
    private void invalidateFutureCache() {
        // cancel and remove requests
        for(Segment segment : mFutureCacheRequests.keySet()) {
            mFutureCacheRequests.get(segment).cancel();
        }
        mFutureCacheRequests.clear();

        // delete and remove files
        for(Segment segment : mFutureCache.keySet()) {
            mFutureCache.get(segment).file.delete();
        }
        mFutureCache.clear();
    }

    /**
     * http://developer.android.com/training/basics/data-storage/files.html
     */
    private File getTempFile(Context context, String fileName) {
        File file = null;
        try {
            file = File.createTempFile(fileName, null, context.getCacheDir());
        } catch (IOException e) {
            // Error while creating file
        }
        return file;
    }

    private void clearTempDir(Context context) {
        for(File file : context.getCacheDir().listFiles()) {
            file.delete();
        }
    }

    /**
     * Builds a request object for a segment.
     */
    private Request buildSegmentRequest(Segment segment) {
        Request.Builder builder = new Request.Builder()
                .url(segment.media);

        if(segment.hasRange()) {
            builder.addHeader("Range", "bytes=" + segment.range);
        }

        return builder.build();
    }

    /**
     * Handles a segment by merging it with the init segment into a temporary file.
     */
    private CachedSegment handleSegment(byte[] mediaSegment, Segment segment) throws IOException {
        File segmentFile = getTempFile(mContext, "seg" + mRepresentation.id + "-" + segment.range + "");
        long segmentPTSOffsetUs = 0;

        if(mMp4Mode) {
            /* The MP4 iso format needs special treatment because the Android MediaExtractor/MediaCodec
             * does not support the fragmented MP4 container format. Each segment therefore needs
             * to be joined with the init fragment and converted to a "conventional" unfragmented MP4
             * container file. */
            IsoFile baseIsoFile = new IsoFile(new MemoryDataSourceImpl(mInitSegments.get(mRepresentation).toByteArray())); // TODO do not go ByteString -> byte[] -> ByteBuffer, find more efficient way (custom mp4parser DataSource maybe?)
            IsoFile fragment = new IsoFile(new MemoryDataSourceImpl(mediaSegment));

            /* The PTS in a converted MP4 always start at 0, so we read the offset from the segment
             * index box and work with it at the necessary places to adjust the local PTS to global
             * PTS concerning the whole stream. */
            SegmentIndexBox sidx = fragment.getBoxes(SegmentIndexBox.class).get(0);
            segmentPTSOffsetUs = (long)((double)sidx.getEarliestPresentationTime() / sidx.getTimeScale() * 1000000);

            Movie mp4Segment = new Movie();
            mp4Segment.addTrack(new Mp4TrackImpl(null, baseIsoFile.getMovieBox().getBoxes(TrackBox.class).get(0), fragment));
            Container mp4SegmentContainer = mMp4Builder.build(mp4Segment);
            FileOutputStream fos = new FileOutputStream(segmentFile, false);
            mp4SegmentContainer.writeContainer(fos.getChannel());
            fos.close();
        } else {
            // merge init and media segments into file
            BufferedSink segmentFileSink = Okio.buffer(Okio.sink(segmentFile));
            segmentFileSink.write(mInitSegments.get(mRepresentation));
            segmentFileSink.write(mediaSegment);
            segmentFileSink.close();
        }

        return new CachedSegment(segmentFile, segmentPTSOffsetUs);
    }

    private class SegmentDownloadCallback implements Callback {

        private Segment mSegment;

        private SegmentDownloadCallback(Segment segment) {
            mSegment = segment;
        }

        @Override
        public void onFailure(Request request, IOException e) {
            if(mFutureCacheRequests.remove(mSegment) != null) {
                Log.e(TAG, "onFailure", e);
            } else {
                // If a call is not in the requests map anymore, it has been cancelled and didn't really fail
            }
        }

        @Override
        public void onResponse(Response response) throws IOException {
            if(response.isSuccessful()) {
                try {
                    long startTime = SystemClock.elapsedRealtime();
                    byte[] segmentData = response.body().bytes();
                    mAdaptationLogic.reportSegmentDownload(mAdaptationSet, mRepresentation, mSegment, segmentData.length, SystemClock.elapsedRealtime() - startTime);
                    CachedSegment cachedSegment = handleSegment(segmentData, mSegment);
                    mFutureCacheRequests.remove(mSegment);
                    mFutureCache.put(mSegment, cachedSegment);
                    Log.d(TAG, "async cached " + mSegment.toString() + " -> " + cachedSegment.file.getPath());
                    synchronized (mFutureCache) {
                        mFutureCache.notify();
                    }
                } catch(Exception e) {
                    Log.e(TAG, "onResponse", e);
                } finally {
                    response.body().close();
                }
            }
        }
    }
}