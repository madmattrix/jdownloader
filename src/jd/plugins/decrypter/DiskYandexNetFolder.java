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
package jd.plugins.decrypter;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;

import org.appwork.utils.StringUtils;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.controlling.ProgressController;
import jd.controlling.linkcrawler.CrawledLink;
import jd.http.Browser;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterException;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.FilePackage;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.PluginJSonUtils;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "disk.yandex.net", "docviewer.yandex.com" }, urls = { "https?://(?:www\\.)?(((((mail|disk)\\.)?yandex\\.(?:net|com|com\\.tr|ru|ua)|yadi\\.sk)/(disk/)?public/(\\?hash=.+|#.+))|(?:yadi\\.sk|yadisk\\.cc)/(?:d|i)/[A-Za-z0-9\\-_]+(/[^/]+){0,}|yadi\\.sk/mail/\\?hash=.+)|https?://yadi\\.sk/a/[A-Za-z0-9\\-_]+", "https?://docviewer\\.yandex\\.(?:net|com|com\\.tr|ru|ua)/\\?url=ya\\-disk\\-public%3A%2F%2F.+" })
public class DiskYandexNetFolder extends PluginForDecrypt {
    public DiskYandexNetFolder(PluginWrapper wrapper) {
        super(wrapper);
    }

    private static final String type_docviewer     = "https?://docviewer\\.yandex\\.[^/]+/\\?url=ya\\-disk\\-public%3A%2F%2F([^/\"\\&]+).*?";
    private final String        type_primaryURLs   = "https?://(?:www\\.)?(((mail|disk)\\.)?yandex\\.(net|com|com\\.tr|ru|ua)|yadi\\.sk)/(disk/)?public/(\\?hash=.+|#.+)";
    private final String        type_shortURLs_d   = "https?://(?:www\\.)?(yadi\\.sk|yadisk\\.cc)/d/[A-Za-z0-9\\-_]+((/[^/]+){0,})";
    private final String        type_shortURLs_i   = "https?://(?:www\\.)?(yadi\\.sk|yadisk\\.cc)/i/[A-Za-z0-9\\-_]+";
    private final String        type_yadi_sk_mail  = "https?://(www\\.)?yadi\\.sk/mail/\\?hash=.+";
    private final String        type_yadi_sk_album = "https?://(www\\.)?yadi\\.sk/a/[A-Za-z0-9\\-_]+";
    private final String        DOWNLOAD_ZIP       = "DOWNLOAD_ZIP_2";
    private static final String OFFLINE_TEXT       = "class=\"not\\-found\\-public__caption\"|_file\\-blocked\"|A complaint was received regarding this file|>File blocked<";
    private static final String JSON_TYPE_DIR      = "dir";
    ArrayList<DownloadLink>     decryptedLinks     = new ArrayList<DownloadLink>();
    private String              addedLink          = null;

    /** Using API: https://tech.yandex.ru/disk/api/reference/public-docpage/ */
    @SuppressWarnings({ "deprecation", "unchecked", "rawtypes" })
    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        br.setFollowRedirects(true);
        CrawledLink current = getCurrentLink();
        String subFolderBase = "";
        while (current != null) {
            if (current.getDownloadLink() != null && getSupportedLinks().matcher(current.getURL()).matches()) {
                final String path = current.getDownloadLink().getStringProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, null);
                if (path != null) {
                    subFolderBase = path;
                }
                break;
            }
            current = current.getSourceLink();
        }
        jd.plugins.hoster.DiskYandexNet.prepbrAPI(this.br);
        this.addedLink = param.toString();
        String hash_decoded = null;
        String fname_url = null;
        String fpName = null;
        String mainhashID = null;
        String path_main = new Regex(this.addedLink, type_shortURLs_d).getMatch(1);
        if (path_main != null) {
            path_main = URLDecoder.decode(path_main, "UTF-8");
        }
        boolean is_part_of_a_folder = false;
        boolean parameter_correct = false;
        final DownloadLink main = createDownloadlink("http://yandexdecrypted.net/" + System.currentTimeMillis() + new Random().nextInt(10000000));
        if (path_main == null) {
            path_main = "/";
        }
        if (this.addedLink.matches(type_yadi_sk_album)) {
            /* Crawl albums */
            crawlPhotoAlbum();
        } else {
            /* Crawl everything else */
            if (this.addedLink.matches(type_docviewer)) {
                /* TODO: Change that --> FILE-URLs --> Should work fine then with the fixed decrypter! */
                /* Documents in web view mode --> File-URLs! */
                /* First lets fix broken URLs by removing unneeded parameters ... */
                final String remove = new Regex(this.addedLink, "(\\&[a-z0-9]+=.+)").getMatch(0);
                if (remove != null) {
                    this.addedLink = this.addedLink.replace(remove, "");
                }
                mainhashID = new Regex(this.addedLink, type_docviewer).getMatch(0);
                mainhashID = new Regex(this.addedLink, "url=ya\\-disk\\-public%3A%2F%2F(.+)").getMatch(0);
                String hash_temp_decoded = Encoding.htmlDecode(mainhashID);
                fname_url = new Regex(this.addedLink, "\\&name=([^/\\&]+)").getMatch(0);
                if (fname_url == null) {
                    fname_url = new Regex(hash_temp_decoded, ":/([^/]+)$").getMatch(0);
                }
                fname_url = Encoding.htmlDecode(fname_url);
            } else if (this.addedLink.matches(type_yadi_sk_mail)) {
                mainhashID = regexHashFromURL(this.addedLink);
                this.addedLink = "https://disk.yandex.com/public/?hash=" + mainhashID;
            } else if (this.addedLink.matches(type_shortURLs_d) || this.addedLink.matches(type_shortURLs_i)) {
                getPage(this.addedLink);
                if (isOffline(this.br)) {
                    final DownloadLink offline = this.createOfflinelink(this.addedLink);
                    main.setFinalFileName(new Regex(this.addedLink, "([A-Za-z0-9\\-_]+)$").getMatch(0));
                    decryptedLinks.add(offline);
                    return decryptedLinks;
                }
                mainhashID = PluginJSonUtils.getJsonValue(br, "hash");
                if (mainhashID == null) {
                    logger.warning("Decrypter broken for link: " + this.addedLink);
                    return null;
                }
                this.addedLink = "https://disk.yandex.com/public/?hash=" + Encoding.urlEncode(mainhashID);
                parameter_correct = true;
            } else {
                this.addedLink = this.addedLink.replace("#", "?hash=");
                mainhashID = regexHashFromURL(this.addedLink);
            }
            hash_decoded = Encoding.htmlDecode(mainhashID);
            if (hash_decoded.contains(":/")) {
                final Regex hashregex = new Regex(hash_decoded, "(.*?):(/.+)");
                mainhashID = hashregex.getMatch(0);
                path_main = hashregex.getMatch(1);
                is_part_of_a_folder = true;
            }
            if (!parameter_correct) {
                this.addedLink = "https://disk.yandex.com/public/?hash=" + mainhashID;
            }
            this.br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            this.br.getHeaders().put("X-Requested-With", "XMLHttpRequest");
            main.setProperty("mainlink", this.addedLink);
            main.setProperty("LINKDUPEID", "copydiskyandexcom" + mainhashID);
            main.setName(mainhashID);
            short offset = 0;
            final short entries_per_request = 200;
            long numberof_entries = 0;
            long filesize_total = 0;
            final FilePackage fp = FilePackage.getInstance();
            do {
                if (this.isAbort()) {
                    logger.info("Decryption aborted by user");
                    return decryptedLinks;
                }
                final String encodedMainPath = Encoding.urlEncode(path_main).replace("+", "%20");
                getPage("https://cloud-api.yandex.net/v1/disk/public/resources?limit=" + entries_per_request + "&offset=" + offset + "&public_key=" + Encoding.urlEncode(mainhashID) + "&path=" + encodedMainPath);
                if (PluginJSonUtils.getJsonValue(br, "error") != null) {
                    decryptedLinks.add(this.createOfflinelink(mainhashID));
                    return decryptedLinks;
                }
                LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaObject(this.br.toString());
                final String type_main = (String) entries.get("type");
                if (!type_main.equals(JSON_TYPE_DIR)) {
                    /* We only have a single file --> Add to downloadliste / host plugin */
                    final DownloadLink dl = createDownloadlink("http://yandexdecrypted.net/" + System.currentTimeMillis() + new Random().nextInt(10000000));
                    if (jd.plugins.hoster.DiskYandexNet.apiAvailablecheckIsOffline(this.br)) {
                        decryptedLinks.add(this.createOfflinelink(mainhashID));
                        return decryptedLinks;
                    }
                    decryptSingleFile(dl, entries);
                    if (is_part_of_a_folder) {
                        dl.setProperty("is_part_of_a_folder", is_part_of_a_folder);
                        /* 2017-04-07: Overwrite previously set path value with correct value. */
                        dl.setProperty("path", path_main);
                    }
                    dl.setProperty("mainlink", this.addedLink);
                    dl.setLinkID(mainhashID + path_main);
                    /* Required by hoster plugin to get filepath (filename) */
                    dl.setProperty("plain_filename", PluginJSonUtils.getJsonValue(br, "name"));
                    if (StringUtils.isNotEmpty(subFolderBase)) {
                        dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, subFolderBase);
                    }
                    decryptedLinks.add(dl);
                    return decryptedLinks;
                }
                final String walk_string = "_embedded/items";
                final ArrayList<Object> resource_data_list = (ArrayList) JavaScriptEngineFactory.walkJson(entries, walk_string);
                if (offset == 0) {
                    /* Set total number of entries on first loop. */
                    numberof_entries = JavaScriptEngineFactory.toLong(JavaScriptEngineFactory.walkJson(entries, "_embedded/total"), 0);
                    fpName = (String) entries.get("name");
                    if (inValidate(fpName)) {
                        /* Maybe our folder has no name. */
                        fpName = mainhashID;
                    }
                    fp.setName(fpName);
                }
                main.setProperty("hash_main", mainhashID);
                mainhashID = Encoding.htmlDecode(mainhashID);
                for (final Object list_object : resource_data_list) {
                    entries = (LinkedHashMap<String, Object>) list_object;
                    final String type = (String) entries.get("type");
                    final String hash = (String) entries.get("public_key");
                    final String path = (String) entries.get("path");
                    final String md5 = (String) entries.get("md5");
                    final String url_preview = (String) entries.get("preview");
                    if (type == null || path == null) {
                        return null;
                    }
                    String name = (String) entries.get("name");
                    if (type.equals(JSON_TYPE_DIR)) {
                        /* Subfolders go back into our decrypter! */
                        final String folderlink = "https://disk.yandex.com/public/?hash=" + Encoding.urlEncode(hash) + "%3A" + Encoding.urlEncode(path);
                        final DownloadLink dl = createDownloadlink(folderlink);
                        if (StringUtils.isNotEmpty(path) && !StringUtils.equals(path, "/")) {
                            dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, path);
                        }
                        decryptedLinks.add(dl);
                    } else {
                        if (name == null || hash == null) {
                            return null;
                        }
                        final DownloadLink dl = createDownloadlink("http://yandexdecrypted.net/" + System.currentTimeMillis() + new Random().nextInt(10000000));
                        decryptSingleFile(dl, entries);
                        final String url_content;
                        if (url_preview != null) {
                            /*
                             * Given preview URLs are bullshit as trhey only e.g. link to a thumbnail of a .pdf file - but we know how to
                             * build good "open in browser" content URLs ...
                             */
                            url_content = "https://docviewer.yandex.com/?url=ya-disk-public%3A%2F%2F" + Encoding.urlEncode(hash) + "%3A" + Encoding.urlEncode(path);
                        } else {
                            /*
                             * We do not have any URL - set main URL.
                             */
                            url_content = "https://disk.yandex.com/public/?hash=" + Encoding.urlEncode(hash);
                        }
                        dl.setProperty("hash_main", hash);
                        dl.setProperty("mainlink", url_content);
                        if (md5 != null) {
                            /* md5 hash is usually given */
                            dl.setMD5Hash(md5);
                        }
                        /* All items decrypted here are part of a folder! */
                        dl.setProperty("is_part_of_a_folder", true);
                        if (StringUtils.isNotEmpty(subFolderBase)) {
                            dl.setProperty(DownloadLink.RELATIVE_DOWNLOAD_FOLDER_PATH, subFolderBase);
                        }
                        dl.setContentUrl(url_content);
                        dl.setLinkID(hash + path);
                        dl._setFilePackage(fp);
                        decryptedLinks.add(dl);
                        distribute(dl);
                        filesize_total += dl.getDownloadSize();
                    }
                    offset++;
                }
                if (resource_data_list.size() < entries_per_request) {
                    /* Fail safe */
                    break;
                }
            } while (offset < numberof_entries);
            if (decryptedLinks.size() == 0) {
                /* Should never happen! */
                logger.info("Probably empty folder");
                return decryptedLinks;
            }
            /* Only add main .zip link if the user added the ROOT link, otherwise we get the ROOT as .zip anyways which makes no sense. */
            final boolean is_root_folder = path_main.equals("/");
            if (is_root_folder && SubConfiguration.getConfig("disk.yandex.net").getBooleanProperty(DOWNLOAD_ZIP, false)) {
                /* User wants a .zip file of the complete (sub) folder --> Do that */
                main.setFinalFileName(fpName + ".zip");
                main.setProperty("plain_filename", fpName + ".zip");
                main.setProperty("path", path_main);
                main.setProperty("is_zipped_folder", true);
                main.setContentUrl(this.addedLink);
                main.setLinkID(mainhashID + path_main);
                main.setDownloadSize(filesize_total);
                main.setAvailable(true);
                decryptedLinks.add(main);
            }
        }
        return decryptedLinks;
    }

    /** For e.g. https://yadi.sk/a/blabla */
    private void crawlPhotoAlbum() throws Exception {
        final ArrayList<String> dupeList = new ArrayList<String>();
        final String domain = "disk.yandex.ru";
        getPage(this.addedLink);
        String sk = jd.plugins.hoster.DiskYandexNet.getSK(this.br);
        if (isOffline(this.br)) {
            decryptedLinks.add(this.createOfflinelink(this.addedLink));
            return;
        }
        // if (StringUtils.isEmpty(sk)) {
        // logger.warning("Failed to get SK value");
        // throw new DecrypterException();
        // }
        String fpName = null;
        final String hashShort = new Regex(this.addedLink, "/a/(.+)").getMatch(0);
        final String public_key = PluginJSonUtils.getJsonValue(br, "public_key");
        final String json_of_first_page = regExJSON(this.br);
        if (StringUtils.isEmpty(public_key)) {
            /* 2018-04-18: This could also mean offline */
            decryptedLinks.add(this.createOfflinelink(this.addedLink));
            return;
        }
        if (StringUtils.isEmpty(sk)) {
            logger.info("Getting new SK value ...");
            br.getPage("https://" + domain + "/auth/status?urlOrigin=" + Encoding.urlEncode(this.addedLink) + "&source=album_web_signin");
            sk = jd.plugins.hoster.DiskYandexNet.getSK(br);
            // for (final String sk_domain : jd.plugins.hoster.DiskYandexNet.sk_domains) {
            // br.getPage("https://" + sk_domain + "/auth/status?urlOrigin=" + Encoding.urlEncode(this.addedLink) +
            // "&source=album_web_signin");
            // sk = jd.plugins.hoster.DiskYandexNet.getSK(br);
            // }
        }
        prepBrAlbum(this.br);
        final int maxItemsPerPage = 40;
        int addedItemsTemp = 0;
        int offset = 0;
        String idItemLast = null;
        final FilePackage fp = FilePackage.getInstance();
        do {
            addedItemsTemp = 0;
            LinkedHashMap<String, Object> entries = null;
            final ArrayList<Object> modelObjects;
            if (offset == 0) {
                /* First loop */
                modelObjects = (ArrayList<Object>) JavaScriptEngineFactory.jsonToJavaObject(json_of_first_page);
                entries = findModel(modelObjects, "album");
                entries = (LinkedHashMap<String, Object>) entries.get("data");
                fpName = (String) entries.get("title");
                if (StringUtils.isEmpty(fpName)) {
                    fpName = hashShort;
                }
                fp.setName(fpName);
            } else {
                br.postPage("https://" + domain + "/album-models/?_m=resources", "_model.0=resources&idContext.0=%2Falbum%2F" + Encoding.urlEncode(public_key) + "&order.0=1&sort.0=order_index&offset.0=" + offset + "&amount.0=" + maxItemsPerPage + "&idItemLast.0=" + Encoding.urlEncode(idItemLast) + "&idClient=undefined" + System.currentTimeMillis() + "&version=" + jd.plugins.hoster.DiskYandexNet.VERSION_YANDEX_PHOTO_ALBUMS + "&sk=" + sk);
                entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(br.toString());
                modelObjects = (ArrayList<Object>) entries.get("models");
            }
            entries = findModel(modelObjects, "resources");
            if (entries == null) {
                logger.warning("Failed to find resource model");
                throw new DecrypterException();
            }
            final ArrayList<Object> mediaObjects = (ArrayList<Object>) JavaScriptEngineFactory.walkJson(entries, "data/resources");
            for (final Object mediao : mediaObjects) {
                entries = (LinkedHashMap<String, Object>) mediao;
                /* Unique id e.g. '/album/<public_key>:<item_id>' */
                final String id = (String) entries.get("id");
                final String item_id = (String) entries.get("item_id");
                if (StringUtils.isEmpty(id) || StringUtils.isEmpty(item_id)) {
                    /* his should never happen */
                    continue;
                } else if (dupeList.contains(id)) {
                    logger.info("Stopping to avoid an endless loop because of duplicates / wrong 'idItemLast.0' value");
                    return;
                }
                final String url = String.format("https://yadi.sk/a/%s/%s", hashShort, item_id);
                final DownloadLink dl = this.createDownloadlink(url);
                dl.setLinkID(hashShort + "/" + item_id);
                jd.plugins.hoster.DiskYandexNet.parseInformationAPIAvailablecheckAlbum(this, dl, entries);
                dl._setFilePackage(fp);
                this.decryptedLinks.add(dl);
                distribute(dl);
                offset++;
                addedItemsTemp++;
                if (StringUtils.isEmpty(idItemLast) && addedItemsTemp == mediaObjects.size()) {
                    /* Important for ajax request - id of our last object */
                    idItemLast = id;
                }
                dupeList.add(id);
                if (this.isAbort()) {
                    return;
                }
            }
        } while (addedItemsTemp >= maxItemsPerPage);
        if (offset == 0) {
            logger.warning("Failed to find items");
            throw new DecrypterException();
        }
    }

    public static String regExJSON(final Browser br) {
        return br.getRegex("<script id=\"models\\-client\" type=\"application/json\">(.*?)</script>").getMatch(0);
    }

    public static Browser prepBrAlbum(final Browser br) {
        br.getHeaders().put("Accept", "application/json, text/javascript, */*; q=0.01");
        return br;
    }

    public static LinkedHashMap<String, Object> findModel(final ArrayList<Object> modelObjects, final String targetModelName) {
        LinkedHashMap<String, Object> entries = null;
        boolean foundResourceModel = false;
        for (final Object modelo : modelObjects) {
            entries = (LinkedHashMap<String, Object>) modelo;
            final String model = (String) entries.get("model");
            if (targetModelName.equalsIgnoreCase(model)) {
                foundResourceModel = true;
                break;
            }
        }
        if (!foundResourceModel) {
            return null;
        }
        return entries;
    }

    public static boolean isOffline(final Browser br) {
        return br.containsHTML(OFFLINE_TEXT) || br.getHttpConnection().getResponseCode() == 404;
    }

    private String regexHashFromURL(final String url) {
        return new Regex(url, "hash=([^&#]+)").getMatch(0);
    }

    private void decryptSingleFile(final DownloadLink dl, final LinkedHashMap<String, Object> entries) throws Exception {
        final AvailableStatus status = jd.plugins.hoster.DiskYandexNet.parseInformationAPIAvailablecheckFiles(this, dl, entries);
        dl.setAvailableStatus(status);
    }

    private void getPage(final String url) throws IOException {
        jd.plugins.hoster.DiskYandexNet.getPage(this.br, url);
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    private boolean inValidate(final String s) {
        if (s == null || s.matches("\\s+") || s.equals("")) {
            return true;
        } else {
            return false;
        }
    }
}
