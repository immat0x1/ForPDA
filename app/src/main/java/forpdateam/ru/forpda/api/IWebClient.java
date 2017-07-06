package forpdateam.ru.forpda.api;

import forpdateam.ru.forpda.client.ForPdaRequest;

/**
 * Created by radiationx on 26.03.17.
 */

public interface IWebClient {
    String MINIMAL_PAGE = "http://4pda.ru/forum/index.php?showforum=200#afterauth";

    String get(String url) throws Exception;

    String getXhr(String url) throws Exception;

    String request(ForPdaRequest request) throws Exception;

    String request(ForPdaRequest request, ProgressListener progressListener) throws Exception;

    String loadAndFindRedirect(String url) throws Exception;

    String getRedirect(String url);

    String getAuthKey();

    void clearCookies();

    interface ProgressListener {
        void onProgress(int percent);
    }
}
