//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.http.requests.PostRequest;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.CryptedLink;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.utils.JDUtilities;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "instagram.com" }, urls = { "instagrammdecrypted://[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?" })
public class InstaGramCom extends PluginForHost {
    @SuppressWarnings("deprecation")
    public InstaGramCom(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
        this.enablePremium(MAINPAGE + "/accounts/login/");
    }

    private String  dllink        = null;
    private boolean server_issues = false;

    @Override
    public String getAGBLink() {
        return MAINPAGE + "/about/legal/terms/#";
    }

    /* Connection stuff */
    private static final boolean RESUME                            = true;
    /* Chunkload makes no sense for pictures/small files */
    private static final int     MAXCHUNKS_pictures                = 1;
    /* 2020-01-21: Multi chunks are possible but it's better not to do this to avoid getting blocked! */
    private static final int     MAXCHUNKS_videos                  = 1;
    private static final int     MAXDOWNLOADS                      = -1;
    private static final String  MAINPAGE                          = "https://www.instagram.com";
    public static final String   QUIT_ON_RATE_LIMIT_REACHED        = "QUIT_ON_RATE_LIMIT_REACHED";
    public static final String   PREFER_SERVER_FILENAMES           = "PREFER_SERVER_FILENAMES";
    public static final String   ONLY_GRAB_X_ITEMS                 = "ONLY_GRAB_X_ITEMS";
    public static final String   ONLY_GRAB_X_ITEMS_NUMBER          = "ONLY_GRAB_X_ITEMS_NUMBER";
    public static final boolean  defaultPREFER_SERVER_FILENAMES    = false;
    public static final boolean  defaultQUIT_ON_RATE_LIMIT_REACHED = false;
    public static final boolean  defaultONLY_GRAB_X_ITEMS          = false;
    public static final int      defaultONLY_GRAB_X_ITEMS_NUMBER   = 25;
    private boolean              is_private_url                    = false;

    public void correctDownloadLink(final DownloadLink link) {
        String newurl = link.getPluginPatternMatcher().replace("instagrammdecrypted://", "https://www.instagram.com/p/");
        if (!newurl.endsWith("/")) {
            /* Add slash to the end to prevent 302 redirect to speed up the download process a tiny bit. */
            newurl += "/";
        }
        link.setPluginPatternMatcher(newurl);
    }

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.correctDownloadLink(link);
        dllink = null;
        server_issues = false;
        is_private_url = link.getBooleanProperty("private_url", false);
        this.setBrowserExclusive();
        /*
         * Decrypter can set this status - basically to be able to handle private urls correctly in host plugin in case users' account gets
         * disabled for whatever reason.
         */
        prepBR(this.br);
        boolean is_logged_in = false;
        final Account aa = AccountController.getInstance().getValidAccount(this);
        if (aa != null) {
            try {
                login(this.br, aa, false);
                is_logged_in = true;
            } catch (final Throwable e) {
                logger.log(e);
            }
        }
        if (this.is_private_url && !is_logged_in) {
            link.getLinkStatus().setStatusText("Login required to download this content");
            return AvailableStatus.UNCHECKABLE;
        }
        dllink = link.getStringProperty("directurl", null);
        /*
         * 2017-04-28: By removing the resolution inside the picture URL, we can download the original image - usually, resolution will be
         * higher than before then but it can also get smaller - which is okay as it is the original content.
         */
        final String resolution_inside_url = new Regex(dllink, "(/s\\d+x\\d+/)").getMatch(0);
        if (resolution_inside_url != null) {
            String drlink = dllink.replace(resolution_inside_url, "/");
            drlink = checkLink(drlink);
            if (drlink != null) {
                dllink = drlink;
            }
        } else {
            dllink = checkLink(dllink);
        }
        if (dllink == null) {
            this.dllink = getFreshDirecturl(link);
            if (this.dllink == null) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Failed to refresh directurl");
            }
            // /* Set releasedate as property */
            // String date = PluginJSonUtils.getJson(this.br, "date");
            // if (date == null || !date.matches("\\d+")) {
            // date = PluginJSonUtils.getJson(this.br, "taken_at_timestamp");
            // }
            // if (date != null && date.matches("\\d+")) {
            // setReleaseDate(link, Long.parseLong(date));
            // }
            String ext = null;
            if (ext == null) {
                ext = getFileNameExtensionFromString(dllink, ".jpg");
            }
            String server_filename = getFileNameFromURL(new URL(dllink));
            if (this.getPluginConfig().getBooleanProperty(PREFER_SERVER_FILENAMES, defaultPREFER_SERVER_FILENAMES) && server_filename != null) {
                server_filename = fixServerFilename(server_filename, ext);
                link.setFinalFileName(server_filename);
            } else {
                // decrypter has set the proper name!
                // if the user toggles PREFER_SERVER_FILENAMES setting many times the name can change.
                final String name = link.getStringProperty("decypter_filename", null);
                if (name != null) {
                    link.setFinalFileName(name);
                } else {
                    // do not change.
                    logger.warning("missing storable, filename will not be renamed");
                }
            }
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openHeadConnection(dllink);
            if (con.getResponseCode() == 403) {
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 10 * 60 * 1000l);
            } else if (con.isOK() && !con.getContentType().contains("html") && !con.getContentType().contains("text")) {
                link.setDownloadSize(con.getLongContentLength());
                /* Save it to have it in case it was re-freshed! */
                link.setProperty("directurl", this.dllink);
            } else {
                /* Will get displayed as unknown error later on */
                server_issues = true;
            }
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
        return AvailableStatus.TRUE;
    }

    private String getFreshDirecturl(final DownloadLink link) throws IOException, PluginException {
        String dllink = null;
        logger.info("Trying to refresh directurl");
        final PluginForDecrypt decrypter = JDUtilities.getPluginForDecrypt(this.getHost());
        decrypter.setBrowser(this.br);
        try {
            final CryptedLink forDecrypter = new CryptedLink(link.getContentUrl());
            final ArrayList<DownloadLink> items = decrypter.decryptIt(forDecrypter, null);
            DownloadLink foundLink = null;
            if (items.size() == 1) {
                foundLink = items.get(0);
            } else {
                String orderID = link.getStringProperty("orderid");
                if (orderID == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                for (final DownloadLink linkTmp : items) {
                    final String orderidTmp = linkTmp.getStringProperty("orderid");
                    if (orderID.equals(orderidTmp)) {
                        foundLink = linkTmp;
                        break;
                    }
                }
            }
            dllink = foundLink.getStringProperty("directurl");
        } catch (final Throwable e) {
            logger.log(e);
        }
        if (dllink == null) {
            /* On failure, check for offline. */
            logger.info("Failed to find fresh directurl --> Checking for offline");
            if (br.getRequest().getHttpConnection().getResponseCode() == 404 || br.containsHTML("Oops, an error occurred")) {
                /*
                 * This will also happen if a user tries to access private urls without being logged in --> Which is why we need to know the
                 * private_url status from the crawler!
                 */
                logger.info("Seems like main URL is offline / post got deleted");
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else {
                logger.info("MainURL seems to be online --> Possible plugin error");
            }
        } else {
            logger.info("Successfully found fresh directurl");
        }
        return dllink;
    }

    private String checkLink(final String flink) throws IOException, PluginException {
        URLConnectionAdapter con = null;
        final Browser br2 = br.cloneBrowser();
        br2.setFollowRedirects(true);
        try {
            con = br2.openHeadConnection(flink);
            if (!con.isOK() || con.getContentType().contains("html") || con.getContentType().contains("text")) {
                return null;
            }
        } catch (final Exception e) {
            logger.log(e);
        } finally {
            if (con != null) {
                try {
                    con.disconnect();
                } catch (final Exception e) {
                }
            }
        }
        return flink;
    }

    public static void setReleaseDate(final DownloadLink dl, final long date) {
        final String targetFormat = "yyyy-MM-dd";
        final Date theDate = new Date(date * 1000);
        final SimpleDateFormat formatter = new SimpleDateFormat(targetFormat);
        final String formattedDate = formatter.format(theDate);
        dl.setProperty("date", formattedDate);
    }

    public static String fixServerFilename(String server_filename, final String correctExtension) {
        final String server_filename_ext = getFileNameExtensionFromString(server_filename, null);
        if (correctExtension != null && server_filename_ext == null) {
            server_filename += correctExtension;
        } else if (correctExtension != null && !server_filename_ext.equalsIgnoreCase(correctExtension)) {
            server_filename = server_filename.replace(server_filename_ext, correctExtension);
        }
        return server_filename;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        if (this.is_private_url) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (server_issues) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown server error", 10 * 60 * 1000l);
        } else if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        handleDownload(link);
    }

    public void handleDownload(final DownloadLink link) throws Exception {
        int maxchunks = MAXCHUNKS_pictures;
        if (link.getFinalFileName() != null && link.getFinalFileName().contains(".mp4") || link.getName() != null && link.getName().contains(".mp4")) {
            maxchunks = MAXCHUNKS_videos;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, RESUME, maxchunks);
        if (dl.getConnection().getContentType().contains("html") || dl.getConnection().getContentType().contains("text")) {
            try {
                br.followConnection(true);
            } catch (IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return MAXDOWNLOADS;
    }

    public static void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                br.setCookiesExclusive(true);
                prepBR(br);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    br.setCookies(MAINPAGE, cookies);
                    br.getPage(MAINPAGE + "/");
                    if (br.getCookies(MAINPAGE).get("sessionid", Cookies.NOTDELETEDPATTERN) == null || br.getCookies(MAINPAGE).get("ds_user_id", Cookies.NOTDELETEDPATTERN) == null) {
                        /* Full login required */
                        br.clearCookies(MAINPAGE);
                    } else {
                        /* Saved cookies were valid */
                        account.saveCookies(br.getCookies(MAINPAGE), "");
                        return;
                    }
                }
                br.getPage(MAINPAGE + "/");
                final String csrftoken = br.getRegex("\"csrf_token\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (csrftoken == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                br.setCookie(MAINPAGE, "csrftoken", csrftoken);
                final String rollout_hash = br.getRegex("\"rollout_hash\"\\s*:\\s*\"(.*?)\"").getMatch(0);
                if (rollout_hash == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                PostRequest post = new PostRequest("https://www.instagram.com/accounts/login/ajax/");
                post.getHeaders().put("Accept", "*/*");
                post.getHeaders().put("X-Instagram-AJAX", rollout_hash);
                post.getHeaders().put("X-CSRFToken", csrftoken);
                post.getHeaders().put("X-Requested-With", "XMLHttpRequest");
                post.setContentType("application/x-www-form-urlencoded");
                /* 2020-05-19: https://github.com/instaloader/instaloader/pull/623 */
                final String enc_password = "#PWD_INSTAGRAM_BROWSER:0:" + System.currentTimeMillis() + ":" + account.getPass();
                post.setPostDataString("username=" + Encoding.urlEncode(account.getUser()) + "&enc_password=" + Encoding.urlEncode(enc_password) + "&queryParams=%7B%7D");
                br.getPage(post);
                if ("fail".equals(PluginJSonUtils.getJsonValue(br, "status"))) {
                    // 2 factor (Coded semi blind).
                    if ("checkpoint_required".equals(PluginJSonUtils.getJsonValue(br, "message"))) {
                        final String page = PluginJSonUtils.getJsonValue(br, "checkpoint_url");
                        br.getPage(page);
                        // verify by email.
                        Form f = br.getFormBySubmitvalue("Verify+by+Email");
                        if (f == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        br.submitForm(f);
                        f = br.getFormBySubmitvalue("Verify+Account");
                        if (f == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        }
                        // dialog here to ask for 2factor verification 6 digit code.
                        final DownloadLink dummyLink = new DownloadLink(null, "Account 2 Factor Auth", MAINPAGE, br.getURL(), true);
                        final String code = getUserInput("2 Factor Authenication\r\nPlease enter in the 6 digit code within your Instagram linked email account", dummyLink);
                        if (code == null) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2 Factor response", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                        f.put("response_code", Encoding.urlEncode(code));
                        // correct or incorrect?
                        if (br.containsHTML(">Please check the code we sent you and try again\\.<")) {
                            throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid 2 Factor response", PluginException.VALUE_ID_PREMIUM_DISABLE);
                        }
                        // now 2factor most likely wont have the authenticated json if statement below....
                        // TODO: confirm what's next.
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, "Unfinished code, please report issue with logs to Development Team.");
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
                if (!br.containsHTML("\"authenticated\"\\s*:\\s*true\\s*")) {
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!\r\nSchnellhilfe: \r\nDu bist dir sicher, dass dein eingegebener Benutzername und Passwort stimmen?\r\nFalls dein Passwort Sonderzeichen enthält, ändere es und versuche es erneut!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!\r\nQuick help:\r\nYou're sure that the username and password you entered are correct?\r\nIf your password contains special characters, change it (remove them) and try again!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                account.saveCookies(br.getCookies(MAINPAGE), "");
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        synchronized (account) {
            login(this.br, account, true);
        }
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setConcurrentUsePossible(true);
        ai.setStatus("Free Account");
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        requestFileInformation(link);
        /* We're already logged in - no need to login again here! */
        this.handleDownload(link);
    }

    public static Browser prepBR(final Browser br) {
        br.getHeaders().put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36");
        br.setCookie(MAINPAGE, "ig_pr", "1");
        // 429 == too many requests, we need to rate limit requests.
        br.setAllowedResponseCodes(new int[] { 400, 429 });
        br.setRequestIntervalLimit("instagram.com", 250);
        br.setFollowRedirects(true);
        return br;
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), PREFER_SERVER_FILENAMES, "Use server-filenames whenever possible?").setDefaultValue(defaultPREFER_SERVER_FILENAMES));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), QUIT_ON_RATE_LIMIT_REACHED, "Abort crawl process once rate limit is reached?").setDefaultValue(defaultQUIT_ON_RATE_LIMIT_REACHED));
        final ConfigEntry grabXitems = new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), ONLY_GRAB_X_ITEMS, "Only grab the X latest items?").setDefaultValue(defaultONLY_GRAB_X_ITEMS);
        getConfig().addEntry(grabXitems);
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_SPINNER, getPluginConfig(), ONLY_GRAB_X_ITEMS_NUMBER, "How many items shall be grabbed?", defaultONLY_GRAB_X_ITEMS_NUMBER, 1025, defaultONLY_GRAB_X_ITEMS_NUMBER).setDefaultValue(defaultONLY_GRAB_X_ITEMS_NUMBER).setEnabledCondidtion(grabXitems, true));
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return MAXDOWNLOADS;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
