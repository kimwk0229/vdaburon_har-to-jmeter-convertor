/*
 * Copyright 2024 Vincent DABURON
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.github.vdaburon.jmeter.har;

import de.sstoehr.harreader.HarReader;
import de.sstoehr.harreader.HarReaderException;
import de.sstoehr.harreader.model.Har;
import de.sstoehr.harreader.model.HarCreatorBrowser;
import de.sstoehr.harreader.model.HarPostData;
import de.sstoehr.harreader.model.HarPostDataParam;
import de.sstoehr.harreader.model.HarRequest;

import io.github.vdaburon.jmeter.har.external.ManageExternalFile;
import io.github.vdaburon.jmeter.har.lrwr.HarLrTransactions;
import io.github.vdaburon.jmeter.har.lrwr.ManageLrwr;
import io.github.vdaburon.jmeter.har.common.TransactionInfo;
import io.github.vdaburon.jmeter.har.websocket.ManageWebSocket;
import io.github.vdaburon.jmeter.har.websocket.WebSocketRequest;

import org.apache.commons.lang3.StringUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.w3c.dom.Document;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.Properties;
import java.util.regex.PatternSyntaxException;

/**
 * The main class to read a har file and generate a JMeter script and a Record.xml file (HAR 파일을 읽고 JMeter 스크립트와 Record.xml 파일을 생성하는 메인 클래스)
 */

public class HarForJMeter {

    public static final String APPLICATION_VERSION = "7.1";

    // CLI OPTIONS
    public static final String K_HAR_IN_OPT = "har_in"; // 입력 HAR 파일
    public static final String K_JMETER_FILE_OUT_OPT = "jmx_out"; // 출력 JMeter JMX 파일
    public static final String K_CREATE_NEW_TC_AFTER_MS_OPT = "new_tc_pause"; // 새 트랜잭션 컨트롤러 생성 시간 (밀리초)
    public static final String K_ADD_PAUSE_OPT = "add_pause"; // 일시 정지 추가 여부
    public static final String K_REGEX_FILTER_INCLUDE_OPT = "filter_include"; // 포함할 URL 정규식 필터
    public static final String K_REGEX_FILTER_EXCLUDE_OPT = "filter_exclude"; // 제외할 URL 정규식 필터
    public static final String K_RECORD_FILE_OUT_OPT = "record_out"; // 출력 Record.xml 파일
    public static final String K_REMOVE_COOKIE_OPT = "remove_cookie"; // 쿠키 제거 여부
    public static final String K_REMOVE_CACHE_REQUEST_OPT = "remove_cache_request"; // 캐시 요청 제거 여부
    public static final String K_PAGE_START_NUMBER = "page_start_number"; // 페이지 시작 번호
    public static final String K_SAMPLER_START_NUMBER = "sampler_start_number"; // 샘플러 시작 번호
    public static final String K_LRWR_USE_INFOS = "use_lrwr_infos"; // LoadRunner Web Recorder 정보 사용 여부
    public static final String K_LRWR_USE_TRANSACTION_NAME = "transaction_name"; // 트랜잭션 이름 사용 여부
    public static final String K_EXTERNAL_FILE_INFOS = "external_file_infos"; // 외부 정보 파일
    public static final String K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE = "add_result_tree_record"; // Record.xml 파일과 함께 View Result Tree 추가 여부
    public static final String K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH = "ws_with_pdoornbosch"; // Peter Doornbosch 플러그인으로 웹소켓 추가 여부


    private static final Logger LOGGER = Logger.getLogger(HarForJMeter.class.getName()); // 로거 인스턴스

    public static void main(String[] args) {
        String harFile = "";
        String jmxOut = "";
        long createNewTransactionAfterRequestMs = 0;
        boolean isAddPause = true;
        String urlFilterToInclude = "";
        String urlFilterToExclude = "";
        String recordXmlOut = "";
        boolean isRemoveCookie = true;
        boolean isRemoveCacheRequest = true;
        boolean isAddViewTreeForRecord = true;
        boolean isWebSocketPDoornbosch = false; // Peter Doornbosch 플러그인으로 웹소켓 처리 여부
        int pageStartNumber = 1; // 페이지 시작 번호
        int samplerStartNumber = 1; // 샘플러 시작 번호
        String lrwr_info = ""; // LoadRunner Web Recorder Chrome 확장 프로그램용
        String fileExternalInfo = ""; // csv file name contains infos like : 2024-05-07T07:56:40.513Z;TRANSACTION;welcome_page;start


        long lStart = System.currentTimeMillis();
        LOGGER.info("Start main");

        Options options = createOptions();
        Properties parseProperties = null;

        try {
            parseProperties = parseOption(options, args);
        } catch (ParseException ex) {
            helpUsage(options);
            LOGGER.info("main end (exit 1) ERROR");
            System.exit(1);
        }

        String sTmp = "";
        sTmp = (String) parseProperties.get(K_HAR_IN_OPT); // HAR 파일 경로 가져오기
        if (sTmp != null) {
            harFile = sTmp;
        }

        sTmp = (String) parseProperties.get(K_JMETER_FILE_OUT_OPT); // JMX 출력 파일 경로 가져오기
        if (sTmp != null) {
            jmxOut = sTmp;
        }

        sTmp = (String) parseProperties.get(K_CREATE_NEW_TC_AFTER_MS_OPT); // 새 트랜잭션 컨트롤러 생성 시간 가져오기
        if (sTmp != null) {
            try {
                createNewTransactionAfterRequestMs = Integer.parseInt(sTmp);
            } catch (Exception ex) {
                LOGGER.warning("Error parsing long parameter " + K_CREATE_NEW_TC_AFTER_MS_OPT + ", value = " + sTmp + ", set to 0 (default)"); // 파싱 오류 경고
                createNewTransactionAfterRequestMs = 0;
            }
        }

        sTmp = (String) parseProperties.get(K_ADD_PAUSE_OPT); // 일시 정지 추가 여부 가져오기
        if (sTmp != null) {
            isAddPause= Boolean.parseBoolean(sTmp);
        }

        sTmp = (String) parseProperties.get(K_REGEX_FILTER_INCLUDE_OPT);
        if (sTmp != null) {
            urlFilterToInclude = sTmp;
        }

        sTmp = (String) parseProperties.get(K_REGEX_FILTER_EXCLUDE_OPT);
        if (sTmp != null) {
            urlFilterToExclude = sTmp;
        }

        sTmp = (String) parseProperties.get(K_RECORD_FILE_OUT_OPT); // Record.xml 출력 파일 경로 가져오기
        if (sTmp != null) {
            recordXmlOut = sTmp;
        }

        sTmp = (String) parseProperties.get(K_REMOVE_COOKIE_OPT); // 쿠키 제거 여부 가져오기
        if (sTmp != null) {
            isRemoveCookie= Boolean.parseBoolean(sTmp);
        }

        sTmp = (String) parseProperties.get(K_REMOVE_CACHE_REQUEST_OPT); // 캐시 요청 제거 여부 가져오기
        if (sTmp != null) {
            isRemoveCacheRequest= Boolean.parseBoolean(sTmp);
        }

        sTmp = (String) parseProperties.get(K_PAGE_START_NUMBER);
        if (sTmp != null) {
            try {
                pageStartNumber = Integer.parseInt(sTmp);
            } catch (Exception ex) { // 파싱 오류 경고
                LOGGER.warning("Error parsing long parameter " + K_PAGE_START_NUMBER + ", value = " + sTmp + ", set to 1 (default)");
                pageStartNumber = 1;
            }
        }
        if (pageStartNumber <= 0) {
            pageStartNumber = 1;
        }

        sTmp = (String) parseProperties.get(K_SAMPLER_START_NUMBER);
        if (sTmp != null) {
            try {
                samplerStartNumber = Integer.parseInt(sTmp);
            } catch (Exception ex) { // 파싱 오류 경고
                LOGGER.warning("Error parsing long parameter " + K_SAMPLER_START_NUMBER + ", value = " + sTmp + ", set to 1 (default)");
                samplerStartNumber = 1;
            }
        }
        if (samplerStartNumber <= 0) {
            samplerStartNumber = 1;
        }

        sTmp = (String) parseProperties.get(K_LRWR_USE_INFOS); // LRWR 정보 사용 여부 가져오기
        if (sTmp != null) {
            lrwr_info= sTmp;
            if (!lrwr_info.isEmpty() && !K_LRWR_USE_TRANSACTION_NAME.equalsIgnoreCase(sTmp)) {
                LOGGER.warning("This Parameter " + K_LRWR_USE_INFOS + " is not an expected value, value = " + sTmp + ", set to empty (default)");
                lrwr_info = "";
            }
        } else {
            lrwr_info = "";
        }

        sTmp = (String) parseProperties.get(K_EXTERNAL_FILE_INFOS); // 외부 정보 파일 경로 가져오기
        if (sTmp != null) {
            fileExternalInfo = sTmp;
        }

        sTmp = (String) parseProperties.get(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE); // View Result Tree 추가 여부 가져오기
        if (sTmp != null) {
            isAddViewTreeForRecord= Boolean.parseBoolean(sTmp);
        }

        sTmp = (String) parseProperties.get(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH); // 웹소켓 플러그인 사용 여부 가져오기
        if (sTmp != null) {
            isWebSocketPDoornbosch= Boolean.parseBoolean(sTmp);
        }

        LOGGER.info("************* PARAMETERS ***************");
        LOGGER.info(K_HAR_IN_OPT + ", harFile=" + harFile);
        LOGGER.info(K_JMETER_FILE_OUT_OPT + ", jmxOut=" + jmxOut);
        LOGGER.info(K_RECORD_FILE_OUT_OPT + ", recordXmlOut=" + recordXmlOut);
        LOGGER.info(K_CREATE_NEW_TC_AFTER_MS_OPT + ", createNewTransactionAfterRequestMs=" + createNewTransactionAfterRequestMs);
        LOGGER.info(K_ADD_PAUSE_OPT + ", isAddPause=" + isAddPause);
        LOGGER.info(K_REGEX_FILTER_INCLUDE_OPT + ", urlFilterToInclude=" + urlFilterToInclude);
        LOGGER.info(K_REGEX_FILTER_EXCLUDE_OPT + ", urlFilterToExclude=" + urlFilterToExclude);
        LOGGER.info(K_REMOVE_COOKIE_OPT + ", isRemoveCookie=" + isRemoveCookie);
        LOGGER.info(K_REMOVE_CACHE_REQUEST_OPT + ", isRemoveCacheRequest=" + isRemoveCacheRequest);
        LOGGER.info(K_PAGE_START_NUMBER + ", pageStartNumber=" + pageStartNumber);
        LOGGER.info(K_SAMPLER_START_NUMBER + ", samplerStartNumber=" + samplerStartNumber);
        LOGGER.info(K_LRWR_USE_INFOS + ", lrwr_info=" + lrwr_info);
        LOGGER.info(K_EXTERNAL_FILE_INFOS + ", fileExternalInfo=" + fileExternalInfo);
        LOGGER.info(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE + ", isAddViewTreeForRecord=" + isAddViewTreeForRecord);
        LOGGER.info(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH + ", isWebSocketPDoornbosch=" + isWebSocketPDoornbosch);
        LOGGER.info("***************************************");
        try {
            generateJmxAndRecord(harFile,  jmxOut,createNewTransactionAfterRequestMs,isAddPause, isRemoveCookie, isRemoveCacheRequest, urlFilterToInclude, urlFilterToExclude,
                                    recordXmlOut, pageStartNumber, samplerStartNumber, lrwr_info, fileExternalInfo, isAddViewTreeForRecord, isWebSocketPDoornbosch);

            long lEnd = System.currentTimeMillis();
            long lDurationMs = lEnd - lStart;
            LOGGER.info("Duration ms : " + lDurationMs);
            LOGGER.info("End main OK exit(0)");
            System.exit(0);

        } catch (HarReaderException | ParserConfigurationException | TransformerException | MalformedURLException | // 예외 처리
                 PatternSyntaxException e) {
            LOGGER.severe(e.toString());
            e.printStackTrace();
            System.exit(1);
        } catch (URISyntaxException e) {
            LOGGER.severe(e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Create the JMeter script jmx file and the Record.xml file (JMeter 스크립트 JMX 파일과 Record.xml 파일을 생성합니다.)
     * @param harFile the har file to read (읽을 HAR 파일)
     * @param jmxOut the JMeter script to create (생성할 JMeter 스크립트)
     * @param recordXmlOut the record.xml file to open with a Listener View Result Tree (리스너 View Result Tree로 열 Record.xml 파일)
     * @param createNewTransactionAfterRequestMs how many milliseconds for creating a new Transaction Controller (새 트랜잭션 컨트롤러를 생성하기 위한 시간(밀리초))
     * @param isAddPause do we add Flow Control Action PAUSE ? (Flow Control Action PAUSE를 추가할 것인가?)
     * @param isRemoveCookie do we remove Cookie information ? (쿠키 정보를 제거할 것인가?)
     * @param isRemoveCacheRequest do we remove the cache information for the Http Request ? (HTTP 요청에 대한 캐시 정보를 제거할 것인가?)
     * @param urlFilterToInclude the regex filter to include url (URL을 포함하기 위한 정규식 필터)
     * @param urlFilterToExclude the regex filter to exclude url (URL을 제외하기 위한 정규식 필터)
     * @param pageStartNumber the first page number (첫 번째 페이지 번호)
     * @param samplerStartNumber the first http sampler number (첫 번째 HTTP 샘플러 번호)
     * @param lrwr_info what information from the HAR do we use ? The transaction_name or empty. For HAR generated with LoadRunner Web Recorder. (HAR에서 어떤 정보를 사용할 것인가? transaction_name 또는 비워둠. LoadRunner Web Recorder로 생성된 HAR용.)
     * @param fileExternalInfo file contains external infos like 2024-05-07T07:56:40.513Z;TRANSACTION;home_page;start (2024-05-07T07:56:40.513Z;TRANSACTION;home_page;start와 같은 외부 정보를 포함하는 파일)
     * @param isAddViewTreeForRecord do we add View Result Tree to view Record.xml file ? (Record.xml 파일을 보기 위해 View Result Tree를 추가할 것인가?)
     * @param isWebSocketPDoornbosch do we find websocket messages and managed websocket with Peter Doornbosch JMeter plugin ? (웹소켓 메시지를 찾아 Peter Doornbosch JMeter 플러그인으로 웹소켓을 관리할 것인가?)
     * @throws HarReaderException trouble when reading HAR file (HAR 파일 읽기 문제)
     * @throws MalformedURLException trouble to convert String to a URL (문자열을 URL로 변환하는 문제)
     * @throws ParserConfigurationException regex expression is incorrect (정규식 표현식이 올바르지 않음)
     * @throws URISyntaxException trouble to convert String to a URL (문자열을 URL로 변환하는 문제)
     * @throws TransformerException Megatron we have a problem (변환기 문제)
     */
    public static void generateJmxAndRecord(String harFile, String jmxOut, long createNewTransactionAfterRequestMs, boolean isAddPause, boolean isRemoveCookie, boolean isRemoveCacheRequest, String urlFilterToInclude, String urlFilterToExclude,
                                            String recordXmlOut, int pageStartNumber, int samplerStartNumber, String lrwr_info, String fileExternalInfo, boolean isAddViewTreeForRecord, boolean isWebSocketPDoornbosch) throws HarReaderException, MalformedURLException, ParserConfigurationException, URISyntaxException, TransformerException {
        HarForJMeter harForJMeter = new HarForJMeter();

        LOGGER.info("Version=" + APPLICATION_VERSION);

        Har har = harForJMeter.loadHarFile(harFile); // HAR 파일 로드
        HarCreatorBrowser creator = har.getLog().getCreator(); // HAR 생성자 정보 가져오기
        String harCreator = "HAR File, Creator : Not Declared"; // HAR 생성자 정보 초기화
        if (creator != null) {
            harCreator = "HAR File, Creator : " + creator.getName() + ", version : " + creator.getVersion();
        }
        LOGGER.info(harCreator);

        List<TransactionInfo> listTransactionInfo = null; // 트랜잭션 정보 리스트 초기화
        if (K_LRWR_USE_TRANSACTION_NAME.equals(lrwr_info)) {
            boolean isHarWithLrwr = ManageLrwr.isHarContainsLrwr(harFile); // HAR 파일에 LRWR 정보가 포함되어 있는지 확인
            if (isHarWithLrwr) {
                List<HarLrTransactions> listHarLrTransactions = ManageLrwr.getListTransactionLrwr(harFile); // LRWR 트랜잭션 리스트 가져오기
                listTransactionInfo = ManageLrwr.createListTransactionInfo(listHarLrTransactions); // 트랜잭션 정보 리스트 생성
            }
        }

        if (!fileExternalInfo.isEmpty()) {
            try {
                listTransactionInfo = ManageExternalFile.createListTransactionInfo(fileExternalInfo); // 외부 파일에서 트랜잭션 정보 리스트 생성
            } catch (Exception e) {
                LOGGER.severe("Can't read file or content : " + fileExternalInfo + ", exception : " + e.toString()); // 파일 읽기 오류 처리
            }
        }

        WebSocketRequest webSocketRequest = null;
        if (isWebSocketPDoornbosch) {
            webSocketRequest = ManageWebSocket.getWebSocketRequest(harFile);
        }
        // JMX 파일 생성 시작 로그
        LOGGER.info("************ Start of JMX file creation (JMeter script file) **"); // JMX 파일 생성 시작
        harForJMeter.convertHarToJmx(har, jmxOut, createNewTransactionAfterRequestMs, isAddPause, isRemoveCookie, isRemoveCacheRequest, urlFilterToInclude, urlFilterToExclude,
                                        pageStartNumber, samplerStartNumber, listTransactionInfo, isAddViewTreeForRecord, webSocketRequest, recordXmlOut);
        LOGGER.info("************ End of JMX file creation              ************"); // JMX 파일 생성 종료

        if (!recordXmlOut.isEmpty()) {
            LOGGER.info("************ Start of Recording XML file creation ************"); // Record XML 파일 생성 시작
            harForJMeter.harToRecordXml(har, recordXmlOut, urlFilterToInclude, urlFilterToExclude, pageStartNumber, samplerStartNumber, webSocketRequest);
            LOGGER.info("************ End of Recording XML file creation   ************"); // Record XML 파일 생성 종료
        }
    }

    /**
     * Load the har file and return the HAR object (HAR 파일을 로드하고 HAR 객체를 반환합니다.)
     * @param fileHar the har to read (읽을 HAR 파일)
     * @return the HAR object (HAR 객체)
     * @throws HarReaderException trouble when reading HAR file (HAR 파일 읽기 문제)
     */
    protected Har loadHarFile(String fileHar) throws HarReaderException {
        Har har = new HarReader().readFromFile(new File(fileHar));
        return har;
    }

    /**
     * Create a JMeter script jmx from the Har file (HAR 파일에서 JMeter 스크립트 JMX를 생성합니다.)
     * @param har the har file to read (읽을 HAR 파일)
     * @param jmxXmlOutFile the JMeter script created (생성된 JMeter 스크립트)
     * @param createNewTransactionAfterRequestMs how many milliseconds for creating a new Transaction Controller (새 트랜잭션 컨트롤러를 생성하기 위한 시간(밀리초))
     * @param isAddPause do we add Flow Control Action PAUSE ? (Flow Control Action PAUSE를 추가할 것인가?)
     * @param isRemoveCookie do we remove Cookie information ? (쿠키 정보를 제거할 것인가?)
     * @param isRemoveCacheRequest do we remove the cache information for the Http Request ? (HTTP 요청에 대한 캐시 정보를 제거할 것인가?)
     * @param urlFilterToInclude the regex filter to include url (URL을 포함하기 위한 정규식 필터)
     * @param urlFilterToExclude the regex filter to exclude url (URL을 제외하기 위한 정규식 필터)
     * @param pageStartNumber the first page number (첫 번째 페이지 번호)
     * @param samplerStartNumber the first http sampler number (첫 번째 HTTP 샘플러 번호)
     * @param listTransactionInfo list with TransactionInfo for HAR generated from LoadRunner Web Recorder (LoadRunner Web Recorder에서 생성된 HAR에 대한 TransactionInfo 목록)
     * @param isAddViewTreeForRecord do we add View Result Tree to view Record.xml file ? (Record.xml 파일을 보기 위해 View Result Tree를 추가할 것인가?)
     * @param webSocketRequest a list of websocket messages (웹소켓 메시지 목록)
     * @param recordXmlOut the record.xml file to open with a Listener View Result Tree (리스너 View Result Tree로 열 Record.xml 파일)
     * @throws ParserConfigurationException regex expression is incorrect (정규식 표현식이 올바르지 않음)
     * @throws TransformerException Megatron we have a problem (변환기 문제)
     * @throws URISyntaxException trouble to convert String to a URI (문자열을 URI로 변환하는 문제)
     */
    protected void convertHarToJmx(Har har, String jmxXmlOutFile, long createNewTransactionAfterRequestMs, boolean isAddPause, boolean isRemoveCookie, boolean isRemoveCacheRequest, String urlFilterToInclude, String urlFilterToExclude,
                                   int pageStartNumber, int samplerStartNumber, List<TransactionInfo> listTransactionInfo, boolean isAddViewTreeForRecord, WebSocketRequest webSocketRequest, String recordXmlOut) throws ParserConfigurationException, TransformerException, URISyntaxException {
        XmlJmx xmlJmx = new XmlJmx();
        Document jmxDocument = xmlJmx.convertHarToJmxXml(har, createNewTransactionAfterRequestMs, isAddPause, isRemoveCookie, isRemoveCacheRequest, urlFilterToInclude, urlFilterToExclude,
                                                            pageStartNumber, samplerStartNumber, listTransactionInfo, isAddViewTreeForRecord, webSocketRequest, recordXmlOut);

        xmlJmx.saveXmFile(jmxDocument, jmxXmlOutFile);
    }

    /**
     * Create the Record.xml file that could be open this a Listener View Results Tree (리스너 View Results Tree로 열 수 있는 Record.xml 파일을 생성합니다.)
     * @param har the har file to read (읽을 HAR 파일)
     * @param jmxXmlOutFile the xml file created (생성된 XML 파일)
     * @param urlFilterToInclude the regex filter to include url (URL을 포함하기 위한 정규식 필터)
     * @param urlFilterToExclude the regex filter to exclude url (URL을 제외하기 위한 정규식 필터)
     * @param pageStartNumber the first page number (첫 번째 페이지 번호)
     * @param samplerStartNumber the first http sampler number (첫 번째 HTTP 샘플러 번호)
     * @param webSocketRequest a list of websocket messages (웹소켓 메시지 목록)
     * @throws ParserConfigurationException regex expression is incorrect (정규식 표현식이 올바르지 않음)
     * @throws TransformerException Megatron we have a problem (변환기 문제)
     * @throws URISyntaxException  trouble to convert String to a URI (문자열을 URI로 변환하는 문제)
     * @throws MalformedURLException trouble to convert String to a URL (문자열을 URL로 변환하는 문제)
     */
    protected void harToRecordXml(Har har, String jmxXmlOutFile, String urlFilterToInclude, String urlFilterToExclude, int pageStartNumber, int samplerStartNumber, WebSocketRequest webSocketRequest) throws ParserConfigurationException, TransformerException, URISyntaxException, MalformedURLException {
        Har2TestResultsXml har2TestResultsXml = new Har2TestResultsXml();
        Document jmxDocument = har2TestResultsXml.convertHarToTestResultXml(har, urlFilterToInclude, urlFilterToExclude, samplerStartNumber, webSocketRequest);

        XmlJmx.saveXmFile(jmxDocument, jmxXmlOutFile);

    }

    /**
     * Special treatment for multi-part (usually upload file) (멀티파트(일반적으로 파일 업로드)에 대한 특별 처리)
     * @param harRequest the harRequest with multipart/form-data; (multipart/form-data를 포함하는 harRequest)
     * @return HarPostData modified with file information and others parameters (파일 정보 및 기타 매개변수로 수정된 HarPostData)
     */
    public static HarPostData extractParamsFromMultiPart(HarRequest harRequest) {
        HarPostData harPostData = harRequest.getPostData(); // HAR 요청에서 PostData를 가져옴
        String mimeType =  harPostData.getMimeType(); // MIME 타입 (예: "multipart/form-data; boundary=---------------------------57886876840140655003344272961")

        HarPostData harPostDataModified = new HarPostData(); // 수정된 HarPostData 객체 생성
        List<HarPostDataParam> listParams = new ArrayList<>(); // 파라미터 목록 초기화

        String boundary = StringUtils.substringAfter(mimeType,"boundary="); // MIME 타입에서 boundary 추출
        LOGGER.fine("boundary=<" + boundary + ">"); // boundary 로깅

        String text = harPostData.getText(); // PostData의 텍스트 내용 가져오기
        String[] tabParams = StringUtils.splitByWholeSeparator(text,"--" + boundary + "\r\n"); // boundary를 기준으로 파라미터 분리
        LOGGER.info("Number of parameters in Multi-Parts=" + tabParams.length); // 멀티파트 파라미터 개수 로깅

        for (int i = 0; i < tabParams.length; i++) { // 각 파라미터에 대해 반복
            String paramInter = tabParams[i]; // 현재 파라미터 블록

            paramInter = paramInter.substring(0,Math.min(512, paramInter.length())); // 파라미터 블록을 최대 512자로 자름
            LOGGER.fine("param=<" + paramInter + ">"); // 파라미터 블록 로깅

            String paramName = StringUtils.substringBetween(paramInter,"Content-Disposition: form-data; name=\"", "\""); // Content-Disposition에서 파라미터 이름 추출
            LOGGER.fine("paramName=<" + paramName + ">"); // 파라미터 이름 로깅

            String paramNameLine = "Content-Disposition: form-data; name=\"" + paramName + "\"\rn"; // 파라미터 이름이 포함된 라인 구성
            String afterParamName = paramInter.substring(paramNameLine.length()); // 파라미터 이름 라인 이후의 내용
            LOGGER.fine("afterParamName=<" + afterParamName + ">"); // 파라미터 이름 이후 내용 로깅

            String paramValue = afterParamName.trim(); // 파라미터 값에서 공백 제거
            String paramValue2 = StringUtils.substringBefore(paramValue,"\r\n"); // 첫 번째 줄바꿈 전까지의 값 추출
            if (paramValue2 != null) { // 값이 존재하면
                paramValue = paramValue2; // 해당 값으로 업데이트
            }
            LOGGER.fine("paramValue=<" + paramValue + ">"); // 파라미터 값 로깅

            String fileName = StringUtils.substringBetween(paramValue,"filename=\"", "\""); // 파일 이름 추출 (파일 업로드인 경우)
            String contentType= StringUtils.substringBetween(afterParamName,"Content-Type: ", "\r\n"); // Content-Type 추출
            LOGGER.fine("fileName=<" + fileName + ">"); // 파일 이름 로깅
            LOGGER.fine("contentType=<" + contentType + ">"); // Content-Type 로깅

            HarPostDataParam harPostDataParam = new HarPostDataParam(); // 새 HarPostDataParam 객체 생성
            harPostDataParam.setName(paramName); // 파라미터 이름 설정

            if (fileName == null) { // 파일 이름이 없으면 (일반 폼 데이터)
                harPostDataParam.setValue(paramValue); // 파라미터 값 설정
            }

            harPostDataParam.setContentType(contentType); // Content-Type 설정
            harPostDataParam.setFileName(fileName); // 파일 이름 설정

            listParams.add(harPostDataParam); // 파라미터 목록에 추가
        }

        harPostDataModified.setParams(listParams);

        return harPostDataModified;
    }

    /**
     * Create the Command Line Parameters Options (명령줄 매개변수 옵션을 생성합니다.)
     * @return Option CLI (CLI 옵션)
     */ // CLI 매개변수 옵션을 생성하는 메서드
    private static Options createOptions() {
        Options options = new Options();

        Option helpOpt = Option.builder("help").hasArg(false).desc("Help and show parameters").build();
        options.addOption(helpOpt);

        Option harFileInOpt = Option.builder(K_HAR_IN_OPT).argName(K_HAR_IN_OPT).hasArg(true) // HAR 입력 파일 옵션
                .required(true).desc("Har file to read (e.g : my_file.har)").build(); // 필수 옵션, 읽을 HAR 파일 지정
        options.addOption(harFileInOpt);

        Option jmeterFileOutOpt = Option.builder(K_JMETER_FILE_OUT_OPT).argName(K_JMETER_FILE_OUT_OPT).hasArg(true) // JMeter 출력 파일 옵션
                .required(true).desc("JMeter file created to write (e.g : script.jmx)").build(); // 필수 옵션, 설명
        options.addOption(jmeterFileOutOpt);

        Option createNewTcOpt = Option.builder(K_CREATE_NEW_TC_AFTER_MS_OPT).argName(K_CREATE_NEW_TC_AFTER_MS_OPT).hasArg(true) // 새 트랜잭션 컨트롤러 생성 시간 옵션
                .required(false)
                .desc("Optional, create new Transaction Controller after request ms, same as jmeter property : proxy.pause, need to be > 0 if set. Usefully for Har created by Firefox or Single Page Application (Angular, ReactJS, VuesJS ...)") // 선택 사항, 요청 후 새 트랜잭션 컨트롤러 생성 시간(밀리초), JMeter 속성 proxy.pause와 동일, 설정 시 0보다 커야 함. Firefox 또는 SPA(Angular, ReactJS, VuesJS 등)로 생성된 HAR에 유용
                .build();
        options.addOption(createNewTcOpt);

        Option addPauseOpt = Option.builder(K_ADD_PAUSE_OPT).argName(K_ADD_PAUSE_OPT).hasArg(true) // 일시 정지 추가 옵션
                .required(false)
                .desc("Optional boolean, add Flow Control Action Pause after Transaction Controller (default true)") // 선택 사항, 트랜잭션 컨트롤러 뒤에 Flow Control Action Pause 추가 여부 (기본값 true)
                .build();
        options.addOption(addPauseOpt);

        Option removeCookieHeaderOpt = Option.builder(K_REMOVE_COOKIE_OPT).argName(K_REMOVE_COOKIE_OPT).hasArg(true) // 쿠키 헤더 제거 옵션
                .required(false)
                .desc("Optional boolean, remove cookie in http header (default true because add a Cookie Manager)") // 선택 사항, HTTP 헤더에서 쿠키 제거 여부 (기본값 true, Cookie Manager 추가 때문)
                .build();
        options.addOption(removeCookieHeaderOpt);

        Option removeCacherHeaderRequestOpt = Option.builder(K_REMOVE_CACHE_REQUEST_OPT).argName(K_REMOVE_CACHE_REQUEST_OPT).hasArg(true) // 캐시 헤더 요청 제거 옵션
                .required(false)
                .desc("Optional boolean, remove cache header in the http request (default true because add a Cache Manager)") // 선택 사항, HTTP 요청에서 캐시 헤더 제거 여부 (기본값 true, Cache Manager 추가 때문)
                .build();
        options.addOption(removeCacherHeaderRequestOpt);

        Option filterRegIncludeOpt = Option.builder(K_REGEX_FILTER_INCLUDE_OPT).argName(K_REGEX_FILTER_INCLUDE_OPT).hasArg(true) // URL 포함 정규식 필터 옵션
                .required(false)
                .desc("Optional, regular expression to include url") // 선택 사항, URL을 포함하기 위한 정규식
                .build();
        options.addOption(filterRegIncludeOpt);

        Option filterRegExcludeOpt = Option.builder(K_REGEX_FILTER_EXCLUDE_OPT).argName(K_REGEX_FILTER_EXCLUDE_OPT).hasArg(true) // URL 제외 정규식 필터 옵션
                .required(false)
                .desc("Optional, regular expression to exclude url") // 선택 사항, URL을 제외하기 위한 정규식
                .build();
        options.addOption(filterRegExcludeOpt);

        Option recordFileOutOpt = Option.builder(K_RECORD_FILE_OUT_OPT).argName(K_RECORD_FILE_OUT_OPT).hasArg(true) // Record.xml 출력 파일 옵션
                .required(false)
                .desc("Optional, file xml contains exchanges likes recorded by JMeter") // 선택 사항, JMeter에 의해 기록된 것과 같은 교환을 포함하는 XML 파일
                .build();
        options.addOption(recordFileOutOpt);

        Option pageStartNumberOpt = Option.builder(K_PAGE_START_NUMBER).argName(K_PAGE_START_NUMBER).hasArg(true) // 페이지 시작 번호 옵션
                .required(false)
                .desc("Optional, the start page number for partial recording (default 1)") // 선택 사항, 부분 녹화를 위한 시작 페이지 번호 (기본값 1)
                .build();
        options.addOption(pageStartNumberOpt);

        Option samplerStartNumberOpt = Option.builder(K_SAMPLER_START_NUMBER).argName(K_SAMPLER_START_NUMBER).hasArg(true) // 샘플러 시작 번호 옵션
                .required(false)
                .desc("Optional, the start sampler number for partial recording (default 1)") // 선택 사항, 부분 녹화를 위한 시작 샘플러 번호 (기본값 1)
                .build();
        options.addOption(samplerStartNumberOpt);

        Option lrwrUseInfosOpt = Option.builder(K_LRWR_USE_INFOS).argName(K_LRWR_USE_INFOS).hasArg(true)
                .required(false)
                .desc("Optional, the har file has been generated with LoadRunner Web Recorder and contains Transaction Name, expected value : 'transaction_name' or don't add this parameter")
                .build();
        options.addOption(lrwrUseInfosOpt);

        Option externalFileInfosOpt = Option.builder(K_EXTERNAL_FILE_INFOS).argName(K_EXTERNAL_FILE_INFOS).hasArg(true) // 외부 정보 파일 옵션
                .required(false)
                .desc("Optional, csv file contains external infos : timestamp transaction name and start or end") // 선택 사항, 타임스탬프, 트랜잭션 이름, 시작 또는 종료와 같은 외부 정보를 포함하는 CSV 파일
                .build();
        options.addOption(externalFileInfosOpt);

        Option addViewResultTreeForRecordOpt = Option.builder(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE).argName(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE).hasArg(true) // Record.xml 파일용 View Result Tree 추가 옵션
                .required(false)
                .desc("Optional boolean, add 'View Result Tree' to view the record.xml file created (default true), record_out must be not empty") // 선택 사항, 생성된 record.xml 파일을 보기 위해 'View Result Tree' 추가 여부 (기본값 true), record_out은 비어 있지 않아야 함
                .build();
        options.addOption(addViewResultTreeForRecordOpt);

        Option addWsPluginPeterDoornboshOpt = Option.builder(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH).argName(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH).hasArg(true) // Peter Doornbosch 플러그인으로 웹소켓 추가 옵션
                .required(false)
                .desc("Optional boolean, Manage websocket messages with the JMeter plugin from Peter DOORNBOSH (default false), if true need the plugin from Peter DOORNBOSH to open the generated script")
                .build();
        options.addOption(addWsPluginPeterDoornboshOpt);

        return options;
    }

    /**
     * Convert the main args parameters to properties
     * @param optionsP the command line options declared (선언된 명령줄 옵션)
     * @param args the cli parameters (CLI 매개변수)
     * @return properties (속성)
     * @throws ParseException can't parse command line parmeter (명령줄 매개변수를 구문 분석할 수 없음)
     * @throws MissingOptionException a parameter is mandatory but not present (필수 매개변수가 없거나 누락됨)
     */ // 주요 args 매개변수를 속성으로 변환하는 메서드
    private static Properties parseOption(Options optionsP, String[] args)
            throws ParseException, MissingOptionException {
        Properties properties = new Properties();

        CommandLineParser parser = new DefaultParser();
        // parse the command line arguments
        CommandLine line = parser.parse(optionsP, args);

        if (line.hasOption("help")) {
            properties.setProperty("help", "help value");
            return properties;
        }

        if (line.hasOption(K_HAR_IN_OPT)) {
            properties.setProperty(K_HAR_IN_OPT, line.getOptionValue(K_HAR_IN_OPT));
        }

        if (line.hasOption(K_JMETER_FILE_OUT_OPT)) {
            properties.setProperty(K_JMETER_FILE_OUT_OPT, line.getOptionValue(K_JMETER_FILE_OUT_OPT));
        }

        if (line.hasOption(K_CREATE_NEW_TC_AFTER_MS_OPT)) {
            properties.setProperty(K_CREATE_NEW_TC_AFTER_MS_OPT, line.getOptionValue(K_CREATE_NEW_TC_AFTER_MS_OPT));
        }

        if (line.hasOption(K_ADD_PAUSE_OPT)) {
            properties.setProperty(K_ADD_PAUSE_OPT, line.getOptionValue(K_ADD_PAUSE_OPT));
        }

        if (line.hasOption(K_REMOVE_COOKIE_OPT)) {
            properties.setProperty(K_REMOVE_COOKIE_OPT, line.getOptionValue(K_REMOVE_COOKIE_OPT));
        }

        if (line.hasOption(K_REMOVE_CACHE_REQUEST_OPT)) {
            properties.setProperty(K_ADD_PAUSE_OPT, line.getOptionValue(K_ADD_PAUSE_OPT));
        }

        if (line.hasOption(K_REGEX_FILTER_INCLUDE_OPT)) {
            properties.setProperty(K_REGEX_FILTER_INCLUDE_OPT, line.getOptionValue(K_REGEX_FILTER_INCLUDE_OPT));
        }

        if (line.hasOption(K_REGEX_FILTER_EXCLUDE_OPT)) {
            properties.setProperty(K_REGEX_FILTER_EXCLUDE_OPT, line.getOptionValue(K_REGEX_FILTER_EXCLUDE_OPT));
        }

        if (line.hasOption(K_RECORD_FILE_OUT_OPT)) {
            properties.setProperty(K_RECORD_FILE_OUT_OPT, line.getOptionValue(K_RECORD_FILE_OUT_OPT));
        }

        if (line.hasOption(K_PAGE_START_NUMBER)) {
            properties.setProperty(K_PAGE_START_NUMBER, line.getOptionValue(K_PAGE_START_NUMBER));
        }

        if (line.hasOption(K_SAMPLER_START_NUMBER)) {
            properties.setProperty(K_SAMPLER_START_NUMBER, line.getOptionValue(K_SAMPLER_START_NUMBER));
        }

        if (line.hasOption(K_LRWR_USE_INFOS)) {
            properties.setProperty(K_LRWR_USE_INFOS, line.getOptionValue(K_LRWR_USE_INFOS));
        }

        if (line.hasOption(K_EXTERNAL_FILE_INFOS)) {
            properties.setProperty(K_EXTERNAL_FILE_INFOS, line.getOptionValue(K_EXTERNAL_FILE_INFOS));
        }

        if (line.hasOption(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE)) {
            properties.setProperty(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE, line.getOptionValue(K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE));
        }

        if (line.hasOption(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH)) {
            properties.setProperty(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH, line.getOptionValue(K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH));
        }

        return properties;
    }

    /**
     * Help to command line parameters
     * @param options the command line options declared (선언된 명령줄 옵션)
     */ // 명령줄 매개변수에 대한 도움말을 표시하는 메서드
    private static void helpUsage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        String footer = "E.g : java -jar har-for-jmeter-<version>-jar-with-dependencies.jar -" + K_HAR_IN_OPT + " myhar.har -" + K_JMETER_FILE_OUT_OPT + " scriptout.jmx -"
                + K_RECORD_FILE_OUT_OPT + " recording.xml -" + K_ADD_VIEW_RESULT_TREE_WITH_RECORD_FILE + " true -" + K_CREATE_NEW_TC_AFTER_MS_OPT + " 5000 -"
                + K_ADD_PAUSE_OPT + " true -" + K_REGEX_FILTER_INCLUDE_OPT + " \"https://mysite/.*\" -" + K_REGEX_FILTER_EXCLUDE_OPT + " \"https://notmysite/*\" -"
                + K_PAGE_START_NUMBER + " 50 -" + K_SAMPLER_START_NUMBER + " 250 -" + K_ADD_WEBSOCKET_WITH_PLUGIN_PETER_DOORNBOSH + " false \n";

        formatter.printHelp(120, HarForJMeter.class.getName(),
                HarForJMeter.class.getName(), options, footer, true);
    }

}
