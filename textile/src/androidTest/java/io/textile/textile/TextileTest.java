package io.textile.textile;

import android.content.Context;
import android.content.IntentFilter;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.apache.commons.io.FileUtils;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.textile.pb.Model;
import io.textile.pb.View.AddThreadConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * Textile tests.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TextileTest {

    @Test
    public void integrationTest() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Wipe repo
        File repo = new File(ctx.getFilesDir(), Textile.REPO_NAME);
        if (repo.exists()) {
            FileUtils.deleteDirectory(repo);
        }

        // Initialize
        String phrase = Textile.initialize(ctx, true, false);
        assertNotEquals("", phrase);

        // Setup events
        Textile.instance().addEventListener(new TextileLoggingListener());

        // manually register a receiver for sync
        RequestsBroadcastReceiver broadcastReceiver = new RequestsBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("io.textile.textile.uploadservice.broadcast.status");
        Textile.instance().getApplicationContext().registerReceiver(broadcastReceiver, intentFilter);

        // start
        Textile.instance().start();

        // check top level api
        assertNotEquals("", Textile.instance().version());
        assertNotEquals("", Textile.instance().gitSummary());
        assertNotEquals("", Textile.instance().summary().getAddress());

        // wait for online
        await().atMost(30, SECONDS).until(isOnline());

        // register a cafe
        AtomicBoolean ready = new AtomicBoolean();
        Textile.instance().cafes.register(BuildConfig.TEST_CAFE_ID, BuildConfig.TEST_CAFE_TOKEN, new Handlers.ErrorHandler() {
            @Override
            public void onComplete() {
                ready.getAndSet(true);
            }

            @Override
            public void onError(Exception e) {
                assertNull(e);
                ready.getAndSet(true);
            }
        });
        await().atMost(30, SECONDS).untilTrue(ready);

        // Add a blob thread
        final Model.Thread blobThread = Textile.instance().threads.add(AddThreadConfig.newBuilder()
                .setName("data")
                .setKey(UUID.randomUUID().toString())
                .setSchema(AddThreadConfig.Schema.newBuilder()
                        .setPreset(AddThreadConfig.Schema.Preset.BLOB)
                        .build())
                .build());
        assertNotEquals("", blobThread.getId());

        // Add a media thread
        final Model.Thread mediaThread = Textile.instance().threads.add(AddThreadConfig.newBuilder()
                .setName("test")
                .setKey(UUID.randomUUID().toString())
                .setSchema(AddThreadConfig.Schema.newBuilder()
                        .setPreset(AddThreadConfig.Schema.Preset.MEDIA)
                        .build())
                .setType(Model.Thread.Type.OPEN)
                .setSharing(Model.Thread.Sharing.SHARED)
                .build());
        assertNotEquals("", mediaThread.getId());

        // add a message
        String blockId = Textile.instance().messages.add(blobThread.getId(), "hello");
        assertNotEquals("", blockId);

        // add some data to the blob thread
        ready.getAndSet(false);
        Textile.instance().files.addData(
                "test".getBytes(), blobThread.getId(), "caption", new Handlers.BlockHandler() {
            @Override
            public void onComplete(Model.Block block) {
                assertNotEquals("", block.getId());
                ready.getAndSet(true);
            }

            @Override
            public void onError(Exception e) {
                assertNull(e);
                ready.getAndSet(true);
            }
        });
        await().atMost(30, SECONDS).untilTrue(ready);

        // Add a single file to the media thread
        final String input1 = TextileTest.getCacheFile(ctx, "TEST0.JPG").getAbsolutePath();
        ready.getAndSet(false);
        Textile.instance().files.addFiles(
                input1, mediaThread.getId(), "caption", new Handlers.BlockHandler() {
                @Override
                public void onComplete(Model.Block block) {
                    assertNotEquals("", block.getId());
                    ready.getAndSet(true);
                }

                @Override
                public void onError(Exception e) {
                    assertNull(e);
                    ready.getAndSet(true);
                }
            });
        await().atMost(30, SECONDS).untilTrue(ready);

        // Add two files at once to the media thread
        final String input2 = TextileTest.getCacheFile(ctx, "TEST1.JPG").getAbsolutePath();
        ready.getAndSet(false);
        Textile.instance().files.addFiles(
                input1 + "," + input2, mediaThread.getId(), "caption", new Handlers.BlockHandler() {
                    @Override
                    public void onComplete(Model.Block block) {
                        assertNotEquals("", block.getId());
                        ready.getAndSet(true);
                    }

                    @Override
                    public void onError(Exception e) {
                        assertNull(e);
                        ready.getAndSet(true);
                    }
                });
        await().atMost(60, SECONDS).untilTrue(ready);

        // Wait for uploads to finish
        Thread.sleep(20000);

        // Destroy
        Textile.instance().destroy();
    }

    private Callable<Boolean> isOnline() {
        return () -> Textile.instance().online();
    }

    private static File getCacheFile(Context context, String filename) throws IOException {
        File file = new File(context.getCacheDir(), filename);
        InputStream inputStream = context.getAssets().open(filename);
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            try {
                byte[] buf = new byte[1024];
                int len;
                while ((len = inputStream.read(buf)) > 0) {
                    outputStream.write(buf, 0, len);
                }
            } finally {
                outputStream.close();
            }
        }
        return file;
    }
}
