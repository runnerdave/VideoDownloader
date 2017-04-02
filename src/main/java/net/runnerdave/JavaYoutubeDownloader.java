package net.runnerdave;

/**
 * This work is licensed under a Creative Commons Attribution 3.0 Unported
 * License (http://creativecommons.org/licenses/by/3.0/). This work is placed
 * into the public domain by the author.
 * taken from: http://stackoverflow.com/a/4834369/5311670
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.text.DecimalFormat;
import java.util.*;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

/**
 * Locally download a YouTube.com video.
 */
public class JavaYoutubeDownloader extends Formatter {

    private static final String scheme = "https";
    private static final String host = "www.youtube.com";
    private static final String YOUTUBE_WATCH_URL_PREFIX = scheme + "://" + host + "/watch?v=";
    private static final String ERROR_MISSING_VIDEO_ID = "Missing video id. Extract from " + YOUTUBE_WATCH_URL_PREFIX + "VIDEO_ID";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows; U; Windows NT 6.1; en-US; rv:1.9.2.13) Gecko/20101203 Firefox/3.6.13";
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String newline = System.getProperty("line.separator");
    private static final Logger log = Logger.getLogger(JavaYoutubeDownloader.class.getCanonicalName());
    private static final Logger rootlog = Logger.getLogger("");
    private static final Pattern commaPattern = Pattern.compile(",");
    private static final Pattern pipePattern = Pattern.compile("\\|");
    private static final Pattern ampPattern = Pattern.compile("&");
    private static final char[] ILLEGAL_FILENAME_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':'};
    private static final int BUFFER_SIZE = 2048;
    private static final DecimalFormat commaFormatNoPrecision = new DecimalFormat("###,###");
    private static final double ONE_HUNDRED = 100;
    private static final double KB = 1024;

    private void usage(String error) {
        if (error != null) {
            System.err.println("Error: " + error);
        }
        System.err.println("usage: JavaYoutubeDownload VIDEO_ID");
        System.err.println();
        System.err.println("Options:");
        System.err.println("\t[-dir DESTINATION_DIR] - Specify output directory.");
        System.err.println("\t[-format FORMAT] - Format number" + newline + "\t\tSee https://en.wikipedia.org/w/index.php?title=YouTube&oldid=461873899#Quality_and_codecs");
        System.err.println("\t[-ua USER_AGENT] - Emulate a browser user agent.");
        System.err.println("\t[-enc ENCODING] - Default character encoding.");
        System.err.println("\t[-verbose] - Verbose logging for downloader component.");
        System.err.println("\t[-verboseall] - Verbose logging for all components (e.g. HttpClient).");
        System.exit(-1);
    }

    public static void main(String[] args) {
        try {
            new JavaYoutubeDownloader().run(args);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void run(String[] args) throws Throwable {
        setupLogging(Level.WARNING, Level.WARNING);

        String videoId = null;
        String outdir = ".";
        int format = 18;
        String encoding = DEFAULT_ENCODING;
        String userAgent = DEFAULT_USER_AGENT;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // Options start with either -, --
            // Do not accept Windows-style args that start with / because of abs
            // paths on linux for file names.
            if (arg.charAt(0) == '-') {

                // For easier processing, convert any double dashes to
                // single dashes
                if (arg.length() > 1 && arg.charAt(1) == '-') {
                    arg = arg.substring(1);
                }

                String larg = arg.toLowerCase();

                // Process the option
                if (larg.equals("-help") || larg.equals("-?") || larg.equals("-usage") || larg.equals("-h")) {
                    usage(null);
                } else if (larg.equals("-verbose")) {
                    setupLogging(Level.ALL, Level.WARNING);
                } else if (larg.equals("-verboseall")) {
                    setupLogging(Level.ALL, Level.ALL);
                } else if (larg.equals("-dir")) {
                    outdir = args[++i];
                } else if (larg.equals("-format")) {
                    format = Integer.parseInt(args[++i]);
                } else if (larg.equals("-ua")) {
                    userAgent = args[++i];
                } else if (larg.equals("-enc")) {
                    encoding = args[++i];
                } else {
                    usage("Unknown command line option " + args[i]);
                }
            } else {
                // Non-option (i.e. does not start with -, --

                videoId = arg;

                // Break if only the first non-option should be used.
                break;
            }
        }

        if (videoId == null) {
            usage(ERROR_MISSING_VIDEO_ID);
        }

        log.fine("Starting");

        if (videoId.startsWith(YOUTUBE_WATCH_URL_PREFIX)) {
            videoId = videoId.substring(YOUTUBE_WATCH_URL_PREFIX.length());
        }
        int a = videoId.indexOf('&');
        if (a != -1) {
            videoId = videoId.substring(0, a);
        }

        File outputDir = new File(outdir);
        String extension = getExtension(format);

        play(videoId, format, encoding, userAgent, outputDir, extension);

        log.fine("Finished");
    }

    private static String getExtension(int format) {
        switch (format) {
            case 18:
            case 22:
                return "mp4";
            case 43:
                return "vorbis";
            case 36:
            case 17:
                return "3gpp";
            default:
                throw new Error("Unsupported format " + format);
        }
    }

    private static void play(String videoId, int format, String encoding, String userAgent, File outputdir, String extension) throws Throwable {
        log.fine("Retrieving " + videoId);
        List<NameValuePair> qparams = new ArrayList<NameValuePair>();
        qparams.add(new BasicNameValuePair("video_id", videoId));
        qparams.add(new BasicNameValuePair("fmt", "" + format));
        URI uri = getUri("get_video_info", qparams);

        CookieStore cookieStore = new BasicCookieStore();
        HttpContext localContext = new BasicHttpContext();
        localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);

        HttpClient httpclient = new DefaultHttpClient();
        HttpGet httpget = new HttpGet(uri);
        if (userAgent != null && userAgent.length() > 0) {
            httpget.setHeader("User-Agent", userAgent);
        }

        log.finer("Executing " + uri);
        HttpResponse response = httpclient.execute(httpget, localContext);
        HttpEntity entity = response.getEntity();
        if (entity != null && response.getStatusLine().getStatusCode() == 200) {
            InputStream instream = entity.getContent();
            String videoInfo = getStringFromInputStream(encoding, instream);
            if (videoInfo != null && videoInfo.length() > 0) {
                List<NameValuePair> infoMap = new ArrayList<NameValuePair>();
                URLEncodedUtils.parse(infoMap, new Scanner(videoInfo), encoding);
                String downloadUrl = null;
                String filename = videoId;

                for (NameValuePair pair : infoMap) {
                    String key = pair.getName();
                    String val = pair.getValue();
                    log.finest(key + "=" + val);
                    if (key.equals("title")) {
                        filename = val;
//                    } else if (key.equals("fmt_url_map")) { OLD VALUE IN YOUTUBE API
                    } else if (key.equals("url_encoded_fmt_stream_map")) {
                        String[] formats = commaPattern.split(val);
                        boolean found = false;
                        for (String fmt : formats) {
                            String[] fmtPieces = ampPattern.split(fmt);
                            if (fmtPieces.length >= 4) {
                                Map<String, String> fmtPiecesMap = populateFormats(fmtPieces);
                                int pieceFormat = Integer.parseInt(fmtPiecesMap.get("itag"));
                                log.warning("Available format=" + pieceFormat);
                                if (pieceFormat == format) {
                                    // found what we want
                                    downloadUrl = fmtPiecesMap.get("url");
                                    found = true;
                                    //uncomment for faster performance, leave commented out to print all available formats
                                    //break;
                                }
                            }
                        }
                        if (!found) {
                            log.warning("Could not find video matching specified format, however some formats of the video do exist (use -verbose).");
                        }
                    }
                }

                filename = cleanFilename(filename);
                if (filename.length() == 0) {
                    filename = videoId;
                } else {
                    filename += "_" + videoId;
                }
                filename += "." + extension;
                File outputfile = new File(outputdir, filename);

                if (downloadUrl != null) {
                    downloadWithHttpClient(userAgent, downloadUrl, outputfile);
                } else {
                    log.severe("Could not find video");
                }
            } else {
                log.severe("Did not receive content from youtube");
            }
        } else {
            log.severe("Could not contact youtube: " + response.getStatusLine());
        }
    }

    private static Map<String, String> populateFormats(String[] fmtPieces) {
        Map<String, String> map = new HashMap<>();
        for (String val : fmtPieces) {
            String[] subPieces = val.split("=");
            try {
                map.put(subPieces[0], URLDecoder.decode(subPieces[1], DEFAULT_ENCODING));
            } catch (UnsupportedEncodingException e) {
                log.warning("could not decode this beast");
            }
        }
        return map;
    }

    private static void downloadWithHttpClient(String userAgent, String downloadUrl, File outputfile) throws Throwable {
        HttpGet httpget2 = new HttpGet(downloadUrl);
        if (userAgent != null && userAgent.length() > 0) {
            httpget2.setHeader("User-Agent", userAgent);
        }

        log.finer("Executing " + httpget2.getURI());
        HttpClient httpclient2 = new DefaultHttpClient();
        HttpResponse response2 = httpclient2.execute(httpget2);
        HttpEntity entity2 = response2.getEntity();
        if (entity2 != null && response2.getStatusLine().getStatusCode() == 200) {
            double length = entity2.getContentLength();
            if (length <= 0) {
                // Unexpected, but do not divide by zero
                length = 1;
            }
            InputStream instream2 = entity2.getContent();
            System.out.println("Writing " + commaFormatNoPrecision.format(length) + " bytes to " + outputfile);
            if (outputfile.exists()) {
                outputfile.delete();
            }
            FileOutputStream outstream = new FileOutputStream(outputfile);
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                double total = 0;
                int count = -1;
                int progress = 10;
                long start = System.currentTimeMillis();
                while ((count = instream2.read(buffer)) != -1) {
                    total += count;
                    int p = (int) ((total / length) * ONE_HUNDRED);
                    if (p >= progress) {
                        long now = System.currentTimeMillis();
                        double s = (now - start) / 1000;
                        int kbpers = (int) ((total / KB) / s);
                        System.out.println(progress + "% (" + kbpers + "KB/s)");
                        progress += 10;
                    }
                    outstream.write(buffer, 0, count);
                }
                outstream.flush();
            } finally {
                outstream.close();
            }
            System.out.println("Done");
        } else {
            log.warning("Could not download video, status code: " + response2.getStatusLine().getStatusCode());
        }
    }

    private static String cleanFilename(String filename) {
        for (char c : ILLEGAL_FILENAME_CHARACTERS) {
            filename = filename.replace(c, '_');
        }
        return filename;
    }

    private static URI getUri(String path, List<NameValuePair> qparams) throws URISyntaxException {
        URI uri = URIUtils.createURI(scheme, host, -1, "/" + path, URLEncodedUtils.format(qparams, DEFAULT_ENCODING), null);
        return uri;
    }

    private void setupLogging(Level myLevel, Level globalLevel) {
        changeFormatter(this);
        explicitlySetAllLogging(myLevel, globalLevel);
    }

    @Override
    public String format(LogRecord arg0) {
        return arg0.getMessage() + newline;
    }

    private static void changeFormatter(Formatter formatter) {
        Handler[] handlers = rootlog.getHandlers();
        for (Handler handler : handlers) {
            handler.setFormatter(formatter);
        }
    }

    private static void explicitlySetAllLogging(Level myLevel, Level globalLevel) {
        rootlog.setLevel(Level.ALL);
        for (Handler handler : rootlog.getHandlers()) {
            handler.setLevel(Level.ALL);
        }
        log.setLevel(myLevel);
        rootlog.setLevel(globalLevel);
    }

    private static String getStringFromInputStream(String encoding, InputStream instream) throws UnsupportedEncodingException, IOException {
        Writer writer = new StringWriter();

        char[] buffer = new char[1024];
        try {
            Reader reader = new BufferedReader(new InputStreamReader(instream, encoding));
            int n;
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
        } finally {
            instream.close();
        }
        String result = writer.toString();
        return result;
    }
}

/**
 * <pre>
 * Exploded results from get_video_info:
 *
 * csi_page_type=embed
 * csn=A67dWI7oLZuA4QLL-564Cw
 * no_get_video_log=1
 * cver=1.20170329
 * t=1
 * cbrver=3.6.13
 * ucid=UCrArM-qk-uGtGAr4ok4NQSA
 * fflags=sidebar_renderers=true&postroll_notify_time_seconds=5&html5_request_sizing_multiplier=0.8&dynamic_ad_break_pause_threshold_sec=0&enable_pla_desktop_shelf=true&html5_reduce_startup_rebuffers=true&html5_min_startup_smooth_target=0.0&html5_disable_audio_slicing=true&kids_asset_theme=server_side_assets&html5_elbow_tracking_tweaks=true&midroll_notify_time_seconds=5&mobile_disable_ad_mob_on_home=true&html5_background_quality_cap=360&sdk_wrapper_levels_allowed=0&html5_spherical_bicubic_mode=0&player_destroy_old_version=true&request_mpu_on_unfilled_ad_break=true&pla_shelf_hovercard=true&playready_on_borg=true&king_crimson_player=false&html5_stale_dash_manifest_retry_factor=1.0&yt_unlimited_pts_skip_ads_promo_desktop_always=true&html5_background_cap_idle_secs=60&html5_pause_manifest_ended=true&yto_enable_ytr_promo_refresh_assets=true&html5_min_secs_between_format_selections=8.0&exo_drm_max_keyfetch_delay_ms=0&interaction_log_delayed_event_batch_size=200&kids_enable_privacy_notice=true&ad_duration_threshold_for_showing_endcap_seconds=15&html5_max_vss_watchtime_ratio=0.0&dynamic_ad_break_seek_threshold_sec=0&mweb_adsense_instreams_disabled_for_android_tablets=true&embed_snippet_includes_version=true&live_readahead_seconds_multiplier=0.8&html5_min_byterate_to_time_out=0&enable_live_state_auth=true&html5_idle_preload_secs=1&html5_use_adaptive_live_readahead=true&kids_enable_terms_servlet=true&html5_ad_no_buffer_abort_after_skippable=true&king_crimson_player_redux=true&html5_max_av_sync_drift=50&mweb_blacklist_progressive_chrome_mobile=true&html5_max_readahead_bandwidth_cap=0&hls_variant_deterministic_output_version=7&html5_connect_timeout_secs=7.0&html5_tight_max_buffer_allowed_bandwidth_stddevs=0.0&disable_new_pause_state3=true&polymer_report_missing_web_navigation_endpoint=false&html5_min_vss_watchtime_to_cut_secs_redux=0.0&log_js_exceptions_fraction=0.20&html5_post_interrupt_readahead=0&html5_min_vss_watchtime_to_cut_secs=0.0&use_new_style=true&show_thumbnail_on_standard=true&html5_local_max_byterate_lookahead=15&stop_using_ima_sdk_gpt_request_activity=true&html5_report_conn=true&html5_new_preloading=true&html5_enable_embedded_player_visibility_signals=true&html5_long_term_bandwidth_window_size=0&html5_burst_less_for_no_bw_data=true&html5_max_buffer_duration=0&html5_always_reload_on_403=true&html5_min_readbehind_cap_secs=0&html5_min_buffer_to_resume=6&yto_feature_hub_channel=false&html5_check_for_reseek=true&live_fresca_v2=true&hls_deterministic_output_version=7&html5_timeupdate_readystate_check=true&html5_max_buffer_health_for_downgrade=15&yto_enable_unlimited_landing_page_yto_features=true&html5_request_size_min_secs=0.0&flex_theater_mode=true&html5_reseek_on_infinite_buffer=true&html5_deadzone_multiplier=1.1&enable_red_carpet_p13n_shelves=true&html5_msi_error_fallback=true&ios_enable_mixin_accessibility_custom_actions=true&html5_tight_max_buffer_allowed_impaired_time=0.0&send_api_stats_ads_asr=true&disable_search_mpu=true&html5_default_quality_cap=0&chrome_promo_enabled=true&html5_retry_media_element_errors_delay=0&mpu_visible_threshold_count=2&doubleclick_gpt_retagging=true&enable_playlist_multi_season=true&fixed_padding_skip_button=true&website_actions_holdback=true&fix_gpt_pos_params=true&lugash_header_by_service=true&spherical_on_android_iframe=true&html5_live_disable_dg_pacing=true&html5_max_buffer_health_for_downgrade_proportion=0.0&ios_disable_notification_preprompt=true&html5_strip_emsg=true&enable_offer_restricts_for_watch_page_offers=true&html5_progressive_signature_reload=true&website_actions_throttle_percentage=1.0&desktop_cleanup_companion_on_instream_begin=true&dash_manifest_version=4&live_chunk_readahead=3&html5_variability_discount=0.5&disable_desktop_homepage_pyv_backfill=true&html5_variability_no_discount_thresh=1.0&vss_dni_delayping=0&html5_vp9_live_whitelist=false&html5_min_readbehind_secs=0&ios_notifications_disabled_subs_tab_promoted_item_promo=true&enable_plus_page_pts=true&html5_trust_platform_bitrate_limits=true&html5_throttle_rate=0.0&html5_audio_preload_duration=2.0&mweb_pu_android_chrome_54_above=true&lugash_header_warmup=true&enable_creator_endscreen_html5_renderers=true&html5_adjust_effective_request_size=true&html5_nnr_downgrade_count=16&html5_min_upgrade_health=0&html5_live_4k_more_buffer=true&enable_ccs_buy_flow_for_chirp=true&ad_video_end_renderer_duration_milliseconds=7000&html5_serverside_biscotti_id_wait_ms=1000&autoplay_time=8000&kids_enable_post_onboarding_red_flow=true&use_fast_fade_in_0s=true&enable_local_channel=true&html5_disable_non_contiguous=true&forced_brand_precap_duration_ms=2000&send_html5_api_stats_ads_abandon=true&kids_enable_server_side_assets=true&use_push_for_desktop_live_chat=true&variable_load_timeout_ms=0&html5_variability_full_discount_thresh=3.0&html5_get_video_info_timeout_ms=0&yto_enable_watch_offer_module=true&android_enable_thumbnail_overlay_side_panel=false&lock_fullscreen=false&html5_playing_event_buffer_underrun=true&html5_suspend_manifest_on_pause=true&legacy_autoplay_flag=true&html5_bandwidth_window_size=0&html5_repredict_interval_secs=0.0&html5_get_video_info_promiseajax=true&use_new_skip_icon=true&html5_allowable_liveness_drift_chunks=2&html5_live_pin_to_tail=true&show_countdown_on_bumper=true&kids_enable_block_servlet=true
 * cbr=Firefox
 * account_playback_token=QUFFLUhqbjlFeVdkMUR5VzB5bzB5U1ZhQ3NnV1lYUUdNZ3xBQ3Jtc0ttTzdCSUtmeXlaanc4aFZrZW1HV2dLVnN3NTJ3TTA1ZE9keDRYWFViajFmOGgxUnlBU0F2NUVVZ2ItTVNoMkc2ZTctZEtTY0gtSTU0NUhZM1ByRDJQODZxZFZacXV2WGZoZllsTTVXWU1JNmRGeVJKaw==
 * ssl=1
 * innertube_api_key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8
 * plid=AAVL_JCGyhG4rXhr
 * allow_embed=1
 * tmi=1
 * player_response={"videoDetails":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/I9OZQg4j6EI/hqdefault.jpg?custom=true\u0026w=168\u0026h=94\u0026stc=true\u0026jpg444=true\u0026jpgq=90\u0026sp=68\u0026sigh=VepCFgmkBJE9TgK1hzY63xsbwZc","width":168,"height":94},{"url":"https://i.ytimg.com/vi/I9OZQg4j6EI/hqdefault.jpg?custom=true\u0026w=196\u0026h=110\u0026stc=true\u0026jpg444=true\u0026jpgq=90\u0026sp=68\u0026sigh=LPMJjL5k7aYE2s13Olw1Tfy0DJc","width":196,"height":110},{"url":"https://i.ytimg.com/vi/I9OZQg4j6EI/hqdefault.jpg?custom=true\u0026w=246\u0026h=138\u0026stc=true\u0026jpg444=true\u0026jpgq=90\u0026sp=68\u0026sigh=3vbO4cnPimJ3igICURMH8RRCKCk","width":246,"height":138},{"url":"https://i.ytimg.com/vi/I9OZQg4j6EI/hqdefault.jpg?custom=true\u0026w=336\u0026h=188\u0026stc=true\u0026jpg444=true\u0026jpgq=90\u0026sp=68\u0026sigh=bAirVaAwSfvQ6QiVe5ks9BZInOo","width":336,"height":188}]}},"adSafetyReason":{}}
 * timestamp=1490923012
 * idpj=-4
 * keywords=Chente,Jimenez,David,Casa
 * atc=a=3&b=nF_fu-uOfke3kkpciDzXcdkzkEg&c=1490923012&d=1&e=I9OZQg4j6EI&c3a=16&c1a=1&c6a=1&hh=0Enys9KL-MFBSCTeT9ChQ8X3bg8
 * gapi_hint_params=m;/_/scs/abc-static/_/js/k=gapi.gapi.en.DTPeBB_SvOA.O/m=__features__/rt=j/d=1/rs=AHpOoo-J3J0yqNDMPVrmQT6j-SBFfGx8oA
 * allow_ratings=1
 * innertube_api_version=v1
 * c=WEB
 * is_listed=1
 * innertube_context_client_version=1.20170329
 * thumbnail_url=https://i.ytimg.com/vi/I9OZQg4j6EI/default.jpg
 * cos=Windows
 * ismb=7760000
 * fmt_list=43/640x360/99/0/0,18/320x240/9/0/115,36/320x240/99/1/0,17/176x144/99/1/0
 * hl=en_US
 * vmap=<?xml version="1.0" encoding="UTF-8"?><vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" xmlns:yt="http://youtube.com" version="1.0"></vmap:VMAP>
 * apiary_host_firstparty=
 * status=ok
 * pltype=contentugc
 * watermark=,https://s.ytimg.com/yts/img/watermark/youtube_watermark-vflHX6b6E.png,https://s.ytimg.com/yts/img/watermark/youtube_hd_watermark-vflAzLcD6.png
 * ldpj=-22
 * video_id=I9OZQg4j6EI
 * swf_player_response=1
 * ptk=youtube_none
 * host_language=en
 * itct=CAEQu2kiEwjO9YKEyf_SAhUbQFgKHcu9B7co6NQB
 * url_encoded_fmt_stream_map=url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3D2D1C3AF2EDBFD33C28BDB78296E0E152D9E3CD8E.CC0C3EA4E18E4E3AE5BDDAE6DDE15CF9BD5D890E%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D1477298%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Cratebypass%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D0.000%26pl%3D23%26itag%3D43%26source%3Dyoutube%26expire%3D1490944612%26mime%3Dvideo%252Fwebm%26lmt%3D1445519460795297%26ratebypass%3Dyes%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&quality=medium&itag=43&type=video%2Fwebm%3B+codecs%3D%22vp8.0%2C+vorbis%22,url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3DADB11B830731885594BEF9A95BCD6F169FC53FCF.C615D4C191B88B2DAE4F3966532E58315AFE41D8%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D2266653%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Cratebypass%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.497%26pl%3D23%26itag%3D18%26source%3Dyoutube%26expire%3D1490944612%26mime%3Dvideo%252Fmp4%26lmt%3D1389935445219363%26ratebypass%3Dyes%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&quality=medium&itag=18&type=video%2Fmp4%3B+codecs%3D%22avc1.42001E%2C+mp4a.40.2%22,url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3D8D010F2DE57E793DFFA1929966B94CB63A5B4DFB.C1273120E023CCDE581A34CC6C3C21F6D07456FE%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D1502529%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.520%26pl%3D23%26itag%3D36%26source%3Dyoutube%26expire%3D1490944612%26mime%3Dvideo%252F3gpp%26lmt%3D1426907316687969%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&quality=small&itag=36&type=video%2F3gpp%3B+codecs%3D%22mp4v.20.3%2C+mp4a.40.2%22,url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3D766AEEB98DF909C3D1C94D07989397C71773AF86.433BFE0AB8B45F4AE85E9828277A8BBD72A08718%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D532678%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.520%26pl%3D23%26itag%3D17%26source%3Dyoutube%26expire%3D1490944612%26mime%3Dvideo%252F3gpp%26lmt%3D1389935446933881%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&quality=small&itag=17&type=video%2F3gpp%3B+codecs%3D%22mp4v.20.3%2C+mp4a.40.2%22
 * Available format=43
 * Available format=18
 * player_error_log_fraction=1.0
 * eventid=A67dWI7oLZuA4QLL-564Cw
 * author=elrunnerdave
 * apiary_host=
 * cl=151729545
 * fexp=9405972,9422596,9428398,9431012,9433221,9434046,9434289,9443492,9444902,9445371,9446054,9446364,9449243,9450471,9451174,9453897,9455191,9456640,9457141,9458230,9458576,9459517,9460073,9460160,9460516,9462160,9462644,9463051,9463496,9463594,9463965,9464739,9465286,9465492,9465533,9466752,9466778,9466778,9466793,9466795,9467030,9467652,9467881,9468483,9469224
 * root_ve_type=27240
 * view_count=193
 * cosver=6.1
 * enablecsi=1
 * vm=CAEQAA
 * adaptive_fmts=bitrate=246941&projection_type=1&xtags=&fps=10&quality_label=240p&lmt=1389935444774110&size=320x240&index=673-812&init=0-672&itag=133&url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3D1716BA8411FCCC06303E76371CC862ED45F8C4EA.E3AE0E7B3A8FDEA0E54CBDC52E2D4CE9F300C690%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D1649402%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Ckeepalive%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.000%26pl%3D23%26itag%3D133%26source%3Dyoutube%26keepalive%3Dyes%26expire%3D1490944612%26mime%3Dvideo%252Fmp4%26lmt%3D1389935444774110%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&type=video%2Fmp4%3B+codecs%3D%22avc1.4d400d%22&clen=1649402,bitrate=109869&projection_type=1&xtags=&fps=10&quality_label=144p&lmt=1389935444708477&size=192x144&index=672-811&init=0-671&itag=160&url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3D0D190521D5DCE21235BA099B2BD133A0C3160BA7.2714974B4ED367824EF1E8C2DE90A0580080F676%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D735279%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Ckeepalive%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.000%26pl%3D23%26itag%3D160%26source%3Dyoutube%26keepalive%3Dyes%26expire%3D1490944612%26mime%3Dvideo%252Fmp4%26lmt%3D1389935444708477%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&type=video%2Fmp4%3B+codecs%3D%22avc1.4d400c%22&clen=735279,xtags=&init=0-591&projection_type=1&itag=140&clen=648370&lmt=1389935446982205&url=https%3A%2F%2Fr6---sn-u2bpouxgoxu-hxas.googlevideo.com%2Fvideoplayback%3Fsignature%3DD382EB98B90686906EE9245540C9123027CF248D.0251C1B9515B1F36CDB1D917DD233F085D7A3088%26mv%3Dm%26pcm2cms%3Dyes%26clen%3D648370%26ipbits%3D0%26initcwndbps%3D970000%26sparams%3Dclen%252Cdur%252Cei%252Cgir%252Cid%252Cinitcwndbps%252Cip%252Cipbits%252Citag%252Ckeepalive%252Clmt%252Cmime%252Cmm%252Cmn%252Cms%252Cmv%252Cpcm2cms%252Cpl%252Crequiressl%252Csource%252Cupn%252Cexpire%26dur%3D54.497%26pl%3D23%26itag%3D140%26source%3Dyoutube%26keepalive%3Dyes%26expire%3D1490944612%26mime%3Daudio%252Fmp4%26lmt%3D1389935446982205%26ei%3DA67dWI7oLZuA4QLL-564Cw%26requiressl%3Dyes%26key%3Dyt6%26ip%3D220.244.145.171%26mt%3D1490922926%26gir%3Dyes%26ms%3Dau%26mm%3D31%26mn%3Dsn-u2bpouxgoxu-hxas%26id%3Do-AGzEHh3hJGmO4GBD3jyhydRuKJx-FAJ_oXJ9VSPwg6NG%26upn%3D6EbWnSlfS-A&index=592-695&type=audio%2Fmp4%3B+codecs%3D%22mp4a.40.2%22&bitrate=95714
 * ppv_remarketing_url=https://www.googleadservices.com/pagead/conversion/971134070/?backend=innertube&cname=1&cver=1_20170329&data=backend%3Dinnertube%3Bcname%3D1%3Bcver%3D1_20170329%3Bdactive%3DNone%3Bdynx_itemid%3DI9OZQg4j6EI%3Bptype%3Dppv&label=iuZUCLmC72YQ9qiJzwM&ptype=ppv&value=0.0673333333333
 * length_seconds=54
 * title=En la casa con el chente
 * of=RrelLVu8jYn6frpWwrYg6Q
 * videostats_playback_base_url=https://s.youtube.com/api/stats/playback?len=55&vm=CAEQAA&docid=I9OZQg4j6EI&ns=yt&el=embedded&fexp=9405972%2C9422596%2C9428398%2C9431012%2C9433221%2C9434046%2C9434289%2C9444902%2C9445371%2C9446054%2C9446364%2C9449243%2C9450471%2C9451174%2C9453897%2C9455191%2C9456640%2C9457141%2C9458230%2C9458576%2C9459517%2C9460073%2C9460160%2C9460516%2C9462160%2C9462644%2C9463051%2C9463496%2C9463594%2C9463965%2C9465286%2C9465492%2C9465533%2C9466752%2C9466778%2C9466778%2C9466793%2C9466795%2C9467030%2C9467881%2C9468483%2C9469224&ei=A67dWI7oLZuA4QLL-564Cw&plid=AAVL_JCGyhG4rXhr&cl=151729545&of=RrelLVu8jYn6frpWwrYg6Q
 * avg_rating=0.0
 * probe_url=https://r4---sn-aigl6n76.googlevideo.com/videogoodput?id=o-AHkS3a1Yqd_RouiOhPIHTntb59Pb6K9hpZ1TsuVbWoDx&source=goodput&range=0-4999&expire=1490926611&ip=220.244.145.171&ms=pm&mm=35&pl=24&nh=IgpwZjAxLmxocjI1Kgw4OS4yMjEuNDMuNTg&sparams=id,source,range,expire,ip,ms,mm,pl,nh&signature=0705808623D241691BA38FFB20FF31E841CA1362.2923CBC06935A690DD8DA7E75DB02E747B81B747&key=cms1
 * cr=AU
 * token=1
 * </pre>
 */
