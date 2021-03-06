/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.upstream.cache;

import android.net.Uri;
import android.test.InstrumentationTestCase;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.testutil.FakeDataSet;
import com.google.android.exoplayer2.testutil.FakeDataSource;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.CacheUtil.CachingCounters;
import com.google.android.exoplayer2.util.Util;

import org.mockito.Answers;
import org.mockito.Mock;

import java.io.EOFException;
import java.io.File;

import static com.google.android.exoplayer2.testutil.CacheAsserts.assertCacheEmpty;
import static com.google.android.exoplayer2.testutil.CacheAsserts.assertCachedData;

/**
 * Tests {@link CacheUtil}.
 */
public class CacheUtilTest extends InstrumentationTestCase {

  /**
   * Abstract fake Cache implementation used by the test. This class must be public so Mockito can
   * create a proxy for it.
   */
  public abstract static class AbstractFakeCache implements Cache {

    // This array is set to alternating length of cached and not cached regions in tests:
    // spansAndGaps = {<length of 1st cached region>, <length of 1st not cached region>,
    //    <length of 2nd cached region>, <length of 2nd not cached region>, ... }
    // Ideally it should end with a cached region but it shouldn't matter for any code.
    private int[] spansAndGaps;
    private long contentLength;

    private void init() {
      spansAndGaps = new int[] {};
      contentLength = C.LENGTH_UNSET;
    }

    @Override
    public long getCachedBytes(String key, long position, long length) {
      for (int i = 0; i < spansAndGaps.length; i++) {
        int spanOrGap = spansAndGaps[i];
        if (position < spanOrGap) {
          long left = Math.min(spanOrGap - position, length);
          return (i & 1) == 1 ? -left : left;
        }
        position -= spanOrGap;
      }
      return -length;
    }

    @Override
    public long getContentLength(String key) {
      return contentLength;
    }
  }

  @Mock(answer = Answers.CALLS_REAL_METHODS) private AbstractFakeCache mockCache;
  private File tempFolder;
  private SimpleCache cache;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.setUpMockito(this);
    mockCache.init();
    tempFolder = Util.createTempDirectory(getInstrumentation().getContext(), "ExoPlayerTest");
    cache = new SimpleCache(tempFolder, new NoOpCacheEvictor());
  }

  @Override
  public void tearDown() throws Exception {
    Util.recursiveDelete(tempFolder);
    super.tearDown();
  }

  public void testGenerateKey() throws Exception {
    assertNotNull(CacheUtil.generateKey(Uri.EMPTY));

    Uri testUri = Uri.parse("test");
    String key = CacheUtil.generateKey(testUri);
    assertNotNull(key);

    // Should generate the same key for the same input
    assertEquals(key, CacheUtil.generateKey(testUri));

    // Should generate different key for different input
    assertFalse(key.equals(CacheUtil.generateKey(Uri.parse("test2"))));
  }

  public void testGetKey() throws Exception {
    Uri testUri = Uri.parse("test");
    String key = "key";
    // If DataSpec.key is present, returns it
    assertEquals(key, CacheUtil.getKey(new DataSpec(testUri, 0, C.LENGTH_UNSET, key)));
    // If not generates a new one using DataSpec.uri
    assertEquals(CacheUtil.generateKey(testUri),
        CacheUtil.getKey(new DataSpec(testUri, 0, C.LENGTH_UNSET, null)));
  }

  public void testGetCachedNoData() throws Exception {
    CachingCounters counters = new CachingCounters();
    CacheUtil.getCached(new DataSpec(Uri.parse("test")), mockCache, counters);

    assertCounters(counters, 0, 0, C.LENGTH_UNSET);
  }

  public void testGetCachedDataUnknownLength() throws Exception {
    // Mock there is 100 bytes cached at the beginning
    mockCache.spansAndGaps = new int[] {100};
    CachingCounters counters = new CachingCounters();
    CacheUtil.getCached(new DataSpec(Uri.parse("test")), mockCache, counters);

    assertCounters(counters, 100, 0, C.LENGTH_UNSET);
  }

  public void testGetCachedNoDataKnownLength() throws Exception {
    mockCache.contentLength = 1000;
    CachingCounters counters = new CachingCounters();
    CacheUtil.getCached(new DataSpec(Uri.parse("test")), mockCache, counters);

    assertCounters(counters, 0, 0, 1000);
  }

  public void testGetCached() throws Exception {
    mockCache.contentLength = 1000;
    mockCache.spansAndGaps = new int[] {100, 100, 200};
    CachingCounters counters = new CachingCounters();
    CacheUtil.getCached(new DataSpec(Uri.parse("test")), mockCache, counters);

    assertCounters(counters, 300, 0, 1000);
  }

  public void testCache() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().setRandomData("test_data", 100);
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    CachingCounters counters = new CachingCounters();
    CacheUtil.cache(new DataSpec(Uri.parse("test_data")), cache, dataSource, counters);

    assertCounters(counters, 0, 100, 100);
    assertCachedData(cache, fakeDataSet);
  }

  public void testCacheSetOffsetAndLength() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().setRandomData("test_data", 100);
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    Uri testUri = Uri.parse("test_data");
    DataSpec dataSpec = new DataSpec(testUri, 10, 20, null);
    CachingCounters counters = new CachingCounters();
    CacheUtil.cache(dataSpec, cache, dataSource, counters);

    assertCounters(counters, 0, 20, 20);

    CacheUtil.cache(new DataSpec(testUri), cache, dataSource, counters);

    assertCounters(counters, 20, 80, 100);
    assertCachedData(cache, fakeDataSet);
  }

  public void testCacheUnknownLength() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().newData("test_data")
        .setSimulateUnknownLength(true)
        .appendReadData(TestUtil.buildTestData(100)).endData();
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    DataSpec dataSpec = new DataSpec(Uri.parse("test_data"));
    CachingCounters counters = new CachingCounters();
    CacheUtil.cache(dataSpec, cache, dataSource, counters);

    assertCounters(counters, 0, 100, 100);
    assertCachedData(cache, fakeDataSet);
  }

  public void testCacheUnknownLengthPartialCaching() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().newData("test_data")
        .setSimulateUnknownLength(true)
        .appendReadData(TestUtil.buildTestData(100)).endData();
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    Uri testUri = Uri.parse("test_data");
    DataSpec dataSpec = new DataSpec(testUri, 10, 20, null);
    CachingCounters counters = new CachingCounters();
    CacheUtil.cache(dataSpec, cache, dataSource, counters);

    assertCounters(counters, 0, 20, 20);

    CacheUtil.cache(new DataSpec(testUri), cache, dataSource, counters);

    assertCounters(counters, 20, 80, 100);
    assertCachedData(cache, fakeDataSet);
  }

  public void testCacheLengthExceedsActualDataLength() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().setRandomData("test_data", 100);
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    Uri testUri = Uri.parse("test_data");
    DataSpec dataSpec = new DataSpec(testUri, 0, 1000, null);
    CachingCounters counters = new CachingCounters();
    CacheUtil.cache(dataSpec, cache, dataSource, counters);

    assertCounters(counters, 0, 100, 1000);
    assertCachedData(cache, fakeDataSet);
  }

  public void testCacheThrowEOFException() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().setRandomData("test_data", 100);
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    Uri testUri = Uri.parse("test_data");
    DataSpec dataSpec = new DataSpec(testUri, 0, 1000, null);

    try {
      CacheUtil.cache(dataSpec, cache, new CacheDataSource(cache, dataSource),
          new byte[CacheUtil.DEFAULT_BUFFER_SIZE_BYTES], null, 0, null,
          /*enableEOFException*/ true);
      fail();
    } catch (EOFException e) {
      // Do nothing.
    }
  }

  public void testCachePolling() throws Exception {
    final CachingCounters counters = new CachingCounters();
    FakeDataSet fakeDataSet = new FakeDataSet().newData("test_data")
        .appendReadData(TestUtil.buildTestData(100))
        .appendReadAction(new Runnable() {
          @Override
          public void run() {
            assertCounters(counters, 0, 100, 300);
          }
        })
        .appendReadData(TestUtil.buildTestData(100))
        .appendReadAction(new Runnable() {
          @Override
          public void run() {
            assertCounters(counters, 0, 200, 300);
          }
        })
        .appendReadData(TestUtil.buildTestData(100)).endData();
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    CacheUtil.cache(new DataSpec(Uri.parse("test_data")), cache, dataSource, counters);

    assertCounters(counters, 0, 300, 300);
    assertCachedData(cache, fakeDataSet);
  }

  public void testRemove() throws Exception {
    FakeDataSet fakeDataSet = new FakeDataSet().setRandomData("test_data", 100);
    FakeDataSource dataSource = new FakeDataSource(fakeDataSet);

    Uri uri = Uri.parse("test_data");
    CacheUtil.cache(new DataSpec(uri), cache,
        // set maxCacheFileSize to 10 to make sure there are multiple spans
        new CacheDataSource(cache, dataSource, 0, 10),
        new byte[CacheUtil.DEFAULT_BUFFER_SIZE_BYTES], null, 0, null, true);
    CacheUtil.remove(cache, CacheUtil.generateKey(uri));

    assertCacheEmpty(cache);
  }

  private static void assertCounters(CachingCounters counters, int alreadyCachedBytes,
      int newlyCachedBytes, int contentLength) {
    assertEquals(alreadyCachedBytes, counters.alreadyCachedBytes);
    assertEquals(newlyCachedBytes, counters.newlyCachedBytes);
    assertEquals(contentLength, counters.contentLength);
  }

}
