package jd.plugins.decrypt;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.Vector;
import java.util.regex.Pattern;

import jd.plugins.DownloadLink;
import jd.plugins.Form;
import jd.plugins.Plugin;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginStep;
import jd.plugins.Regexp;
import jd.plugins.RequestInfo;
import jd.utils.JDLocale;
import jd.utils.JDUtilities;

public class RsLayerCom extends PluginForDecrypt {

    final static String host             = "rs-layer.com";
    private String      version          = "0.3";
    private Pattern     patternSupported = getSupportPattern("http://[*]rs-layer\\.com/[+]\\.html");
    
    private static String 	strCaptchaPattern 	= "<img src=\"(captcha-[^\"]*\\.png)\" ";
    private static Pattern 	linkPattern			= Pattern.compile("onclick=\"getFile\\('([^;]*)'\\)");

    public RsLayerCom() {
    	
        super();
        steps.add(new PluginStep(PluginStep.STEP_DECRYPT, null));
        currentStep = steps.firstElement();
        
    }

    @Override
    public String getCoder() {
        return "eXecuTe";
    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public String getPluginID() {
        return host+"-"+version;
    }

    @Override
    public String getPluginName() {
        return host;
    }

    @Override
    public Pattern getSupportedLinks() {
        return patternSupported;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public PluginStep doStep(PluginStep step, String parameter) {
        
    	if(step.getStep() == PluginStep.STEP_DECRYPT) {
            
    		Vector<DownloadLink> decryptedLinks = new Vector<DownloadLink>();
    		
            try {
            	
        		RequestInfo reqinfo = getRequest(new URL(parameter));
                
            	if ( parameter.indexOf("rs-layer.com/link-") != -1 ) {
                    
                    String link = getBetween(reqinfo.getHtmlCode(),"<iframe src=\"", "\" ");
                    link = decryptEntities(link);
                    
                    progress.setRange(1);
                    decryptedLinks.add(this.createDownloadlink(link));
                    progress.increase(1);
                    step.setParameter(decryptedLinks);
                    
                } else if ( parameter.indexOf("rs-layer.com/directory-") != -1 ) {
                	
                    Form[] forms = Form.getForms(reqinfo);
                    
                    // links are captcha protected
                    // by signed
                    if ( forms != null && forms.length != 0 && forms[0] != null ) {
                    	
                    	Form captchaForm = forms[0];
                    	
                    	// find captcha url
                    	String captchaFileName = new Regexp(reqinfo.getHtmlCode(), strCaptchaPattern).getFirstMatch(1);
                    	
                    	if ( captchaFileName == null ){
                    		logger.severe(JDLocale.L("plugins.decrypt.rslayer.couldntFindCaptchaUrl", "Captcha Url konnte nicht gefunden werden"));
                    		step.setStatus(PluginStep.STATUS_ERROR);
                    		return null;
                    	}
                    	
                    	// download captcha
                    	String captchaUrl =  "http://" + host + "/" + captchaFileName;
                    	File captchaFile = getLocalCaptchaFile(this, ".png");
                    	boolean fileDownloaded = JDUtilities.download(captchaFile,
                                    getRequestWithoutHtmlCode(new URL(captchaUrl),
                                    reqinfo.getCookie(), null, true).getConnection()
                                    );
                    	
                    	if (!fileDownloaded) {
                    		logger.info(JDLocale.L("plugins.decrypt.general.captchaDownloadError", "Captcha Download gescheitert"));
                    		step.setStatus(PluginStep.STATUS_ERROR);
                    		return null;
                    	}
                    	
                    	String captchaCode = Plugin.getCaptchaCode(captchaFile, this);
                    	
                    	if(null == captchaCode || captchaCode.isEmpty()){
                    		logger.info(JDLocale.L("plugins.decrypt.rslayer.invalidCaptchaCode", "ungültiger Captcha Code"));
                    		step.setStatus(PluginStep.STATUS_ERROR);
                    		return null;
                    	}
                    	
                    	captchaForm.put("captcha_input", captchaCode);
                    	
                    	reqinfo = readFromURL((HttpURLConnection)captchaForm.getConnection());
                    	
                    	if( reqinfo.containsHTML("Sicherheitscode<br />war nicht korrekt")){
                    		logger.info(JDLocale.L("plugins.decrypt.general.captchaCodeWrong", "Captcha Code falsch"));
                    		step.setStatus(PluginStep.STATUS_ERROR);
                    		return null;
                    	}
                    	
                    	if( reqinfo.containsHTML("Gültigkeit für den<br> Sicherheitscode<br>ist abgelaufen")){
                        
                    		logger.info(JDLocale.L("plugins.decrypt.rslayer.captchaExpired", "Sicherheitscode abgelaufen"));
                    		step.setStatus(PluginStep.STATUS_ERROR);
                    		return null;
                    		
                    	}
                    
                    }
                    
                    Vector<String> layerLinks = getAllSimpleMatches(reqinfo.getHtmlCode(), linkPattern, 1);
                    progress.setRange(layerLinks.size());
                    
                    for(String fileId : layerLinks){
                    	
                    	String layerLink = "http://rs-layer.com/link-"+fileId + ".html";
                    	
                    	RequestInfo request2 = getRequest(new URL(layerLink));
                    	String link = getBetween(request2.getHtmlCode(),"<iframe src=\"", "\" ");
                    	
                    	decryptedLinks.add(this.createDownloadlink(link));
                    	progress.increase(1);
                    	
                    }
                    
                    step.setParameter(decryptedLinks);

                }
            	
            } catch(IOException e) {
                 e.printStackTrace();
            }
            
        }
    	
        return null;
        
    }

    @Override
    public boolean doBotCheck(File file) {
        return false;
    }
    
    // Zeichencode-Entities (&#124 etc.) in normale Zeichen umwandeln
    private String decryptEntities(String str) {
    	
        Vector<Vector<String>> codes = getAllSimpleMatches(str,"&#°;");
        String decodedString = "";
        
        for( int i=0; i<codes.size(); i++ ) {
        	
            int code = Integer.parseInt(codes.get(i).get(0));
            char[] asciiChar = {(char)code};
            decodedString += String.copyValueOf(asciiChar);
            
        }
        
        return decodedString;
        
    }  
    
}