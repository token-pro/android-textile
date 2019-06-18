package io.textile.textile;

import android.content.Context;

import net.gotev.uploadservice.BinaryUploadRequest;
import net.gotev.uploadservice.HttpUploadRequest;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadStatusDelegate;

import java.util.Map;

import io.textile.pb.Model.CafeHTTPRequest;
import io.textile.pb.View.Strings;
import mobile.Callback;
import mobile.Mobile_;

/**
 * Handles HTTP requests queued by the Textile node
 */
class RequestHandler extends NodeDependent {
    private Context applicationContext;
    private int batchSize;
    private boolean flushing;

    RequestHandler(final Mobile_ node, final Context applicationContext, final int batchSize) {
        super(node);
        this.applicationContext = applicationContext;
        this.batchSize = batchSize;
        this.flushing = false;
    }

    void flush() {
        if (flushing) {
            return;
        }
        flushing = true;

        try {
            // 1. List a batch of request ids
            final Strings ids = Strings.parseFrom(node.cafeRequests(batchSize));

            // 2. Write each request to disk
            for (final String id : ids.getValuesList()) {
                node.writeCafeRequest(id, new Callback() {
                    @Override
                    public void call(byte[] bytes, Exception e) {
                        if (e != null) {
                            // noop for now
                            return;
                        }
                        try {
                            CafeHTTPRequest req = CafeHTTPRequest.parseFrom(bytes);

                            // 3. Mark request as pending
                            node.cafeRequestPending(id);

                            // 4. Start the request
                            String uploadId = startUpload(id, req);

                        } catch (Exception exception) {
                            // noop for now
                        }
                    }
                });
            }
        } catch (Exception e) {
            flushing = false;
        }

        flushing = false;
    }

    private String startUpload(final String id, final CafeHTTPRequest req) throws Exception {
        UploadStatusDelegate statusDelegate = new UploadStatusDelegate() {
            @Override
            public void onProgress(Context context, UploadInfo uploadInfo) {
                // @todo emit progress events
            }

            @Override
            public void onError(Context context, UploadInfo uploadInfo, ServerResponse serverResponse, Exception exception) {
                String message = "Request failed (";
                if (serverResponse != null) {
                    message += "code=" + serverResponse.getHttpCode() + " ";
                    message += "body=" + serverResponse.getBodyAsString() + " ";
                }

                if (exception != null) {
                    message += "error=" + exception.getMessage();
                } else {
                    message += "error=unknown";
                }
                message += ")";

                try {
                    node.failCafeRequest(id, message);
                } catch (final Exception e) {
                    // noop
                }
            }

            @Override
            public void onCompleted(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {
                try {
                    node.completeCafeRequest(id);
                } catch (final Exception e) {
                    // noop
                }
            }

            @Override
            public void onCancelled(Context context, UploadInfo uploadInfo) {
                try {
                    node.failCafeRequest(id, "Request cancelled");
                } catch (final Exception e) {
                    // noop
                }
            }
        };

        HttpUploadRequest request = new BinaryUploadRequest(applicationContext, id, req.getUrl())
                .setFileToUpload(req.getPath())
                .setMethod(req.getType().toString())
                .setMaxRetries(2)
                .setDelegate(statusDelegate);

        for (Map.Entry<String,String> entry : req.getHeadersMap().entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }

        return request.startUpload();
    }
}
