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

import de.sstoehr.harreader.model.Har;
import de.sstoehr.harreader.model.HarContent;
import de.sstoehr.harreader.model.HarCookie;
import de.sstoehr.harreader.model.HarEntry;
import de.sstoehr.harreader.model.HarHeader;
import de.sstoehr.harreader.model.HarPostData;
import de.sstoehr.harreader.model.HarPostDataParam;
import de.sstoehr.harreader.model.HarRequest;
import de.sstoehr.harreader.model.HarResponse;
import de.sstoehr.harreader.model.HarTiming;

import io.github.vdaburon.jmeter.har.websocket.WebSocketRequest;
import io.github.vdaburon.jmeter.har.websocket.WebSocketPDoornboshResultXml;

import org.apache.commons.lang3.StringUtils;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Har2TestResultsXml {

    private static final Logger LOGGER = Logger.getLogger(Har2TestResultsXml.class.getName());

    /**
     * HAR(HTTP Archive) 파일을 JMeter의 테스트 결과 XML 형식으로 변환합니다.
     *
     * @param har HAR 객체
     * @param urlFilterToInclude 포함할 URL을 필터링하는 정규식 (비어 있으면 모든 URL 포함)
     * @param urlFilterToExclude 제외할 URL을 필터링하는 정규식 (비어 있으면 모든 URL 제외)
     * @param samplerStartNumber 샘플러 시작 번호
     * @param webSocketRequest WebSocket 요청 처리 객체
     * @return JMeter 테스트 결과 XML Document 객체
     */
    protected Document convertHarToTestResultXml(Har har, String urlFilterToInclude, String urlFilterToExclude, int samplerStartNumber, WebSocketRequest webSocketRequest) throws ParserConfigurationException, URISyntaxException {

        Pattern patternUrlInclude = null;
        if (!urlFilterToInclude.isEmpty()) {
            patternUrlInclude = Pattern.compile(urlFilterToInclude);
        }

        Pattern patternUrlExclude = null;
        if (!urlFilterToExclude.isEmpty()) {
            patternUrlExclude = Pattern.compile(urlFilterToExclude);
        }

        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
        Document document = documentBuilder.newDocument();

        Element eltTestResults = createTestResults(har, document, patternUrlInclude, patternUrlExclude, samplerStartNumber, webSocketRequest);
        document.appendChild(eltTestResults);

        return document;
    }

    /**
     * JMeter 테스트 결과 XML의 최상위 요소인 'testResults'를 생성합니다.
     * HAR 엔트리들을 순회하며 HTTP 샘플 또는 WebSocket 샘플을 생성하여 추가합니다.
     *
     * @param har HAR 객체
     * @param document XML Document 객체
     * @param patternUrlInclude 포함할 URL 정규식 패턴
     * @param patternUrlExclude 제외할 URL 정규식 패턴
     * @param samplerStartNumber 샘플러 시작 번호
     * @param webSocketRequest WebSocket 요청 처리 객체
     * @return 생성된 'testResults' Element 객체
     */
    protected Element createTestResults(Har har, Document document, Pattern patternUrlInclude, Pattern patternUrlExclude, int samplerStartNumber, WebSocketRequest webSocketRequest) throws URISyntaxException {
        Element eltTestResults = document.createElement("testResults");
        Attr attrTrversion = document.createAttribute("version");
        attrTrversion.setValue("1.2");
        eltTestResults.setAttributeNode(attrTrversion);

        List<HarEntry> lEntries = har.getLog().getEntries();
        String currentUrl = "";
        int num = samplerStartNumber;

        for (int e = 0; e < lEntries.size(); e++) {
            HarEntry harEntryInter = lEntries.get(e);

            HarRequest harRequest = harEntryInter.getRequest();
            currentUrl = harRequest.getUrl();

            // URL 포함 필터링 적용
            boolean isAddThisRequest = true;
            if (patternUrlInclude != null) {
                Matcher matcher = patternUrlInclude.matcher(currentUrl);
                isAddThisRequest = matcher.find();
            }
            // URL 제외 필터링 적용
            if (patternUrlExclude != null) {
                Matcher matcher = patternUrlExclude.matcher(currentUrl);
                isAddThisRequest = !matcher.find();
            }

            HashMap hAddictional = (HashMap<String, Object>) harEntryInter.getAdditional();
            if (hAddictional != null) { // HAR 추가 정보 확인
                String fromCache = (String) hAddictional.get("_fromCache");
                if (fromCache != null) {
                    // this url content is in the browser cache (memory or disk) no need to create a new request
                    isAddThisRequest = false;
                }
            }

            String sURl = harEntryInter.getRequest().getUrl();
            URI uri = new URI(sURl);
            String scheme = uri.getScheme();

            // data: 프로토콜은 JMeter에서 지원하지 않으므로 제외
            if ("data".equalsIgnoreCase(scheme)) {
                // jmeter don't support data:image protocole
                isAddThisRequest = false;
            }
            // WebSocket 요청 처리
            if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
                 if (isAddThisRequest && webSocketRequest != null) {
                    num = WebSocketPDoornboshResultXml.createWsSample(document, eltTestResults, harEntryInter, num, webSocketRequest);
                    num = num + 2;
                }
                isAddThisRequest = false; // already added
            }

            // 요청을 추가할 경우 HTTP 샘플 생성
            if (isAddThisRequest) {
                Element eltHttpSample = createHttpSample(document, harEntryInter, num);
                eltTestResults.appendChild(eltHttpSample);
                num++;
            }
        }

        LOGGER.info("testResuts file contains " + num + " httpSample or wsSample");
        return eltTestResults;
    }


    /**
     * JMeter의 'httpSample' 요소를 생성합니다.
     * HAR 엔트리의 요청 및 응답 정보를 기반으로 샘플러의 속성과 자식 요소들을 채웁니다.
     *
     * @param document XML Document 객체
     * @param harEntry HAR 엔트리 객체
     * @param num 샘플러 번호
     * @return 생성된 'httpSample' Element 객체
     */
    protected Element createHttpSample(Document document, HarEntry harEntry, int num) throws URISyntaxException {

        HarRequest harRequest = harEntry.getRequest();
        HarResponse harResponse = harEntry.getResponse();

        Element eltHttpSample = createEltHttpSample(document, harEntry, num);

        // 요청 헤더 생성 및 추가
        Element eltRequestponseHeaders = createRequestHeaders(document, harEntry.getRequest());
        eltHttpSample.appendChild(eltRequestponseHeaders);
        // 응답 헤더 생성 및 추가
        Element eltResponseHeaders = createResponseHeaders(document, harEntry.getResponse());
        eltHttpSample.appendChild(eltResponseHeaders);

        // responseFile 요소 추가 (JMeter 형식에 맞춤)
        Element eltresponseFile = document.createElement("responseFile");
        eltresponseFile = addAttributeToElement(document, eltresponseFile, "class", "java.lang.String");
        eltHttpSample.appendChild(eltresponseFile);

        // 쿠키 정보 생성 및 추가
        Element eltcookies = createCookies(document, harRequest);
        eltHttpSample.appendChild(eltcookies);

        // HTTP 메서드 정보 생성 및 추가
        Element eltmethod = document.createElement("method");
        eltmethod = addAttributeToElement(document, eltmethod, "class", "java.lang.String");
        String method = harRequest.getMethod().name();
        eltmethod.setTextContent(method);
        eltHttpSample.appendChild(eltmethod);

        // 쿼리 문자열 생성 및 추가
        Element eltqueryString = document.createElement("queryString");
        eltqueryString = addAttributeToElement(document, eltqueryString, "class", "java.lang.String");

        String queryString = "";
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) &&
                harRequest.getQueryString().size() > 0) {
            queryString = new URI(harRequest.getUrl()).getQuery();
        } else {
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                queryString = createQueryStringForPostOrPutOrPatch(harRequest);
            }
        }

        eltqueryString.setTextContent(queryString);
        eltHttpSample.appendChild(eltqueryString);

        // 리다이렉트 URL 정보 추가
        String urlRedirect = harResponse.getRedirectURL();
        if (urlRedirect != null && !urlRedirect.isEmpty()) {
            Element eltredirectLocation = document.createElement("redirectLocation");
            eltqueryString = addAttributeToElement(document, eltredirectLocation, "class", "java.lang.String");
            eltredirectLocation.setTextContent(urlRedirect);
            eltHttpSample.appendChild(eltredirectLocation);
        }

        // java.net.URL 요소 추가
        Element eltJavaNetUrl = document.createElement("java.net.URL");
        eltJavaNetUrl.setTextContent(harRequest.getUrl());
        eltHttpSample.appendChild(eltJavaNetUrl);

        // 응답 데이터 생성 및 추가 (텍스트/바이너리 구분)
        boolean isText = false;
        String dt_reponse = eltHttpSample.getAttribute("dt"); // dt 속성으로 텍스트 여부 확인
        if ("text".equalsIgnoreCase(dt_reponse)) {
            isText = true;
        }

        Element eltResponseData = createEltReponseData(document, harResponse, isText);
        eltHttpSample.appendChild(eltResponseData);

        return eltHttpSample;
    }


    /**
     * JMeter의 'httpSample' 요소의 기본 속성들을 생성합니다.
     * HAR 엔트리의 시간, 응답 상태, 요청/응답 크기 등을 기반으로 속성 값을 설정합니다.
     *
     * @param document XML Document 객체
     * @param harEntry HAR 엔트리 객체
     * @param num 샘플러 번호
     * @return 속성이 설정된 'httpSample' Element 객체
     */
    protected Element createEltHttpSample(Document document, HarEntry harEntry, int num) throws URISyntaxException {
        /*
        <httpSample t="18" it="0" lt="18" ct="9" ts="1699889754878" s="true" lb="002 /gestdocqualif/styles/styles.css" rc="200" rm="OK" tn="" dt="text" de="" by="7904" sc="1" ec="0" ng="0" na="0" hn="browser">

        // https://jmeter.apache.org/usermanual/listeners.html#attributes
        /*
        Attribute	Content
        by	Bytes
        sby	Sent Bytes
        de	Data encoding
        dt	Data type
        ec	Error count (0 or 1, unless multiple samples are aggregated)
        hn	Hostname where the sample was generated
        it	Idle Time = time not spent sampling (milliseconds) (generally 0)
        lb	Label
        lt	Latency = time to initial response (milliseconds) - not all samplers support this
        ct	Connect Time = time to establish the connection (milliseconds) - not all samplers support this
        na	Number of active threads for all thread groups
        ng	Number of active threads in this group
        rc	Response Code (e.g. 200)
        rm	Response Message (e.g. OK)
        s	Success flag (true/false)
        sc	Sample count (1, unless multiple samples are aggregated)
        t	Elapsed time (milliseconds)
        tn	Thread Name
        ts	timeStamp (milliseconds since midnight Jan 1, 1970 UTC)
        varname	Value of the named variable
         */
        HarTiming harTimings = harEntry.getTimings();
        HarResponse harResponse = harEntry.getResponse();
        HarRequest harRequest = harEntry.getRequest();
        HarContent harContent = harResponse.getContent();
        // JMeter 샘플러 속성 값 추출 및 설정
        String t_time = "" + harEntry.getTime();
        String it_time = "0";
        String lt_time = "" + harTimings.getWait();
        String ct_time = "" + harTimings.getConnect();
        String ts_time = "" + harEntry.getStartedDateTime().getTime();
        String s_response = "true";
        if (harResponse.getStatus() >= 400) { // 응답 코드가 400 이상이면 실패로 간주
            s_response = "false";
        }
        URI urlRequest = new URI(harRequest.getUrl());
        String lb_label = String.format("%03d " + urlRequest.getPath(), num); // 003 /gestdocqualif/servletStat
        String rc_response = "" + harResponse.getStatus();
        String rm_response = harResponse.getStatusText();
        // MIME 타입과 URL 경로를 기반으로 데이터 타입 (text/bin) 결정
        String urlPath = urlRequest.getPath();
        String dt_response = textFromMimeType(harContent.getMimeType(), urlPath);
        /* encoding
        "response": {
            "status": 200,
                    "statusText": "OK",
                    "httpVersion": "HTTP/1.1",
                    "headers": [
            {
                "name": "Content-Length",
                    "value": "1379"
            },
            {
                "name": "Content-Type",
                    "value": "text/html;charset=ISO-8859-1"
            }
            */
        String de_response = responseEncoding(harResponse);
        // 바이트 크기 및 샘플러 카운트 설정
        String by_response = "" + harContent.getSize(); // Bytes receive
        String sby_request = "" + harRequest.getBodySize();	// Sent Bytes
        String sc_count = "1";
        String ec_count = "0"; // 에러 카운트
        String ng_count = "0";
        String na_count = "0";
        String hn = "browser";

        Element eltHttpSample = document.createElement("httpSample");
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "t", t_time);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "it", it_time);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "lt", lt_time);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "ct", ct_time);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "ts", ts_time);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "s", s_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "lb", lb_label);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "rc", rc_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "rm", rm_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "dt", dt_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "de", de_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "by", by_response);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "sby", sby_request);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "sc", sc_count);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "ec", ec_count);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "ng", ng_count);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "na", na_count);
        eltHttpSample = addAttributeToElement(document, eltHttpSample, "hn", hn);

        return eltHttpSample;
    }

    /**
     * 주어진 Element에 속성을 추가합니다.
     *
     * @param document XML Document 객체
     * @param element 속성을 추가할 Element 객체
     * @param attributeName 속성 이름
     * @param attributeValue 속성 값
     * @return 속성이 추가된 Element 객체
     */
    public static Element addAttributeToElement(Document document, Element element, String attributeName, String attributeValue) {
        Attr attr = document.createAttribute(attributeName);
        attr.setValue(attributeValue);
        element.setAttributeNode(attr);

        return element;
    }
    /**
     * 속성을 생성합니다. (현재 사용되지 않음)
     *
     * @param document XML Document 객체
     * @param attributeName 속성 이름
     * @param attributeValue 속성 값
     * @return 생성된 Attr 객체
     */
    protected Attr createAttribute(Document document, String attributeName, String attributeValue) {
        Attr attr = document.createAttribute(attributeName);
        attr.setValue(attributeValue);

        return attr;
    }
    /**
     * JMeter의 'requestHeader' 요소를 생성하고 요청 헤더 정보를 채웁니다.
     *
     * @param document XML Document 객체
     * @param harRequest HAR 요청 객체
     * @return 생성된 'requestHeader' Element 객체
     */
    public static Element createRequestHeaders(Document document, HarRequest harRequest) {
        Element eltRequestHeader = document.createElement("requestHeader");
        eltRequestHeader = addAttributeToElement(document, eltRequestHeader, "class", "java.lang.String");
        List<HarHeader> lRequestHeaders =  harRequest.getHeaders();

        StringBuffer sb = new StringBuffer(2048);
        for (int i = 0; i < lRequestHeaders.size(); i++) {
            HarHeader harHeader = lRequestHeaders.get(i);
            sb.append(harHeader.getName());
            sb.append(": ");
            sb.append(harHeader.getValue());
            sb.append("\n");
        }
        eltRequestHeader.setTextContent(sb.toString());

        return eltRequestHeader;
    }

    /**
     * JMeter의 'responseHeader' 요소를 생성하고 응답 헤더 정보를 채웁니다.
     *
     * @param document XML Document 객체
     * @param harResponse HAR 응답 객체
     * @return 생성된 'responseHeader' Element 객체
     */
    public static Element createResponseHeaders(Document document, HarResponse harResponse) {
        Element eltResponseHeader = document.createElement("responseHeader");
        eltResponseHeader = addAttributeToElement(document, eltResponseHeader, "class", "java.lang.String");
        List<HarHeader> lResponseHeaders =  harResponse.getHeaders();

        StringBuffer sb = new StringBuffer(2048);
        for (int i = 0; i < lResponseHeaders.size(); i++) {
            HarHeader harHeader = lResponseHeaders.get(i);
            sb.append(harHeader.getName());
            sb.append(": ");
            sb.append(harHeader.getValue());
            sb.append("\n");
        }
        eltResponseHeader.setTextContent(sb.toString());

        return eltResponseHeader;
    }

    /**
     * JMeter의 'cookies' 요소를 생성하고 요청 쿠키 정보를 채웁니다.
     *
     * @param document XML Document 객체
     * @param harRequest HAR 요청 객체
     * @return 생성된 'cookies' Element 객체
     */
    public static Element createCookies(Document document, HarRequest harRequest) {
        Element eltcookies = document.createElement("cookies");
        eltcookies = addAttributeToElement(document, eltcookies, "class", "java.lang.String");
        List<HarCookie> lCookies =  harRequest.getCookies();

        StringBuffer sb = new StringBuffer(2048);
        for (int i = 0; i < lCookies.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            HarCookie cookie = lCookies.get(i);
            sb.append(cookie.getName());
            sb.append("=");
            sb.append(cookie.getValue());
        }
        eltcookies.setTextContent(sb.toString());

        return eltcookies;
    }

    /**
     * JMeter의 'responseData' 요소를 생성하고 응답 본문 데이터를 채웁니다.
     * Base64 인코딩된 텍스트 데이터는 디코딩하여 저장합니다.
     *
     * @param document XML Document 객체
     * @param harResponse HAR 응답 객체
     * @param isText 응답 데이터가 텍스트인지 여부
     * @return 생성된 'responseData' Element 객체
     */
    public static Element createEltReponseData(Document document, HarResponse harResponse, boolean isText) {
        Element eltresponseData = document.createElement("responseData");
        eltresponseData = addAttributeToElement(document, eltresponseData, "class", "java.lang.String");

        HarContent harContent = harResponse.getContent();
        if (harContent != null) {
            String contentText = harContent.getText();
            String contentEncoding = harContent.getEncoding();

            if (contentText != null && "base64".equalsIgnoreCase(contentEncoding) && isText) {
                byte[] contentDecodeByte = Base64.getDecoder().decode(contentText.getBytes());
                String contentDecodeString = new String(contentDecodeByte);
                eltresponseData.setTextContent(contentDecodeString);
            }

            if (contentText != null && contentEncoding == null && isText) {
                eltresponseData.setTextContent(contentText);
            }
        }

        return eltresponseData;
    }

    /**
     * POST, PUT, PATCH 요청에 대한 쿼리 문자열(요청 본문)을 생성합니다.
     * application/x-www-form-urlencoded 및 multipart/form-data 형식을 처리합니다.
     * @param harRequest HAR 요청 객체
     * @return 생성된 쿼리 문자열
     */
    protected String createQueryStringForPostOrPutOrPatch(HarRequest harRequest) {
        StringBuffer sb = new StringBuffer(2048);
        HarPostData postData = harRequest.getPostData();
        String mimeType = postData.getMimeType();
        String mimeTypeExtract = Utils.extractMimeType(mimeType);
        boolean isParamAdd = false;

        if ("application/x-www-form-urlencoded".equalsIgnoreCase(mimeTypeExtract)) {
            List<HarPostDataParam> lDataParams = postData.getParams();
            for (int i = 0; i < lDataParams.size(); i++) {
                if (i > 0) {
                    sb.append("&");
                }
                HarPostDataParam dataParamInter = lDataParams.get(i);
                String name = dataParamInter.getName();
                String value = dataParamInter.getValue();
                sb.append(name);
                sb.append("=");
                sb.append(value);
            }
            isParamAdd = true;
        }

        if (isParamAdd == false && mimeTypeExtract != null && mimeTypeExtract.contains("multipart/form-data")) {
            HarPostData postDataFormData = HarForJMeter.extractParamsFromMultiPart(harRequest);
            String boundary = StringUtils.substringAfter(mimeType,"boundary=");
            LOGGER.fine("boundary=<" + boundary + ">");
            List<HarPostDataParam> listParams  = postDataFormData.getParams();
            StringBuffer sbFormData = new StringBuffer(1024);

            for (int j = 0; j < listParams.size(); j++) {
                HarPostDataParam harPostDataParamInter = listParams.get(j);
                String fileName = harPostDataParamInter.getFileName();
                String contentType = harPostDataParamInter.getContentType();
                String nameParam = harPostDataParamInter.getName();
                String valueParam = harPostDataParamInter.getValue();

                sbFormData.append(boundary + "\n");
                sbFormData.append("Content-Disposition: form-data; name=\"");
                sbFormData.append(nameParam + "\"");

                if (fileName != null) {
                    sbFormData.append("; filename=\"" + fileName + "\"\n");
                    sbFormData.append("Content-Type: " + contentType + "\n");
                    sbFormData.append("\n\n");
                    sbFormData.append("<actual file content, not shown here>");
                    sbFormData.append("\n");
                } else {
                    sbFormData.append("\n\n");
                    sbFormData.append(valueParam);
                    sbFormData.append("\n");
                }
            }
            sb.append(sbFormData);
            // sb.append(postData.getText()); // not the text because may contain binary from the upload file (e.g. PDF or DOCX ...)
            isParamAdd = true;
        }

        if (!isParamAdd) {
            sb.append(postData.getText());
            isParamAdd = true;
        }

        return sb.toString();
    }

    /**
     * 주어진 MIME 타입이 텍스트 기반인지 여부를 판단합니다.
     * @param mimeType MIME 타입 문자열
     * @return 텍스트 기반이면 true, 그렇지 않으면 false
     */
    public static boolean isTextFromMimeType(String mimeType) {
        boolean isText = false;

        String mimeTypeInter = mimeType;
        String partBeforeSemiColum = StringUtils.substringBefore(mimeType, ";"); // application/xml; charset=UTF-8 => application/xml
        if (partBeforeSemiColum != null) {
            mimeTypeInter = partBeforeSemiColum;
        }

        String type = StringUtils.substringBefore(mimeTypeInter, "/"); // application
        String subType = StringUtils.substringAfter(mimeTypeInter, "/"); // xml

        if (subType == null || type.isEmpty()) {
            return false;
        }

        if ("text".equalsIgnoreCase(type)) {
            isText = true;
        }

        if (subType.contains("-xml") || subType.contains("+xml") || subType.contains("-json") || subType.contains("+json")) {
            isText = true;
        }

        if (subType.contains("gzip") || subType.contains("zip") || subType.contains("compressed") || subType.equalsIgnoreCase("octet-stream")) {
            isText = false;
        }

        switch (subType) {
            case "css":
            case "html":
            case "csv":
            case "richtext":
            case "x-www-form-urlencoded":
            case "javascript":
            case "x-javascript":
            case "json":
            case "xml":
            case "xhtml":
            case "xhtml+xml":
            case "atom+xml":
            case "postscript":
            case "base64":
            case "problem+json":
            isText = true;
        }

        return isText;
    }

    /**
     * 주어진 MIME 타입과 URL 경로를 기반으로 JMeter의 'dt' (데이터 타입) 속성 값을 결정합니다.
     * MIME 타입이 부정확할 경우 파일 확장자를 사용하여 텍스트/바이너리 여부를 판단합니다.
     * @param mimeType MIME 타입 문자열
     * @param urlPath URL 경로
     * @return "text" 또는 "bin"
     */
    public static String textFromMimeType(String mimeType, String urlPath) {
        String sText = "text";

        if (!isTextFromMimeType(mimeType)) {
            sText = "bin";
        }

        if("text".equals(sText) &&  urlPath != null && urlPath.contains(".")) {
            // some time the mime type is incorrect so use extension to find if the content is text or bin
            String extension = urlPath.substring(urlPath.lastIndexOf(".") + 1);
            if (extension != null && !extension.isEmpty()) {
                extension = extension.toLowerCase();

                switch (extension) {
                    case "bmp":
                    case "gif":
                    case "ico":
                    case "jpg":
                    case "jpeg":
                    case "png":
                    case "swf":
                    case "eot":
                    case "otf":
                    case "ttf":
                    case "mp3":
                    case "mp4":
                    case "avi":
                    case "mkv":
                    case "wav":
                    case "woff":
                    case "woff2":
                    case "docx":
                    case "doc":
                    case "odt":
                    case "pptx":
                    case "xlsx":
                    case "xls":
                    case "vsdx":
                        sText = "bin";
                }
            }
        }

        return sText;
    }

    /**
     * HAR 응답 헤더에서 응답 인코딩(charset)을 추출합니다.
     * @param harResponse HAR 응답 객체
     * @return 추출된 인코딩 문자열 (기본값: "UTF-8")
     */
    public static String responseEncoding(HarResponse harResponse) {
        List<HarHeader> lResponseHeaders =  harResponse.getHeaders();
        String encoding = "UTF-8";

        for (int i = 0; i < lResponseHeaders.size(); i++) {
            HarHeader harHeader = lResponseHeaders.get(i);
            String name = harHeader.getName();
            String value = harHeader.getValue();

            if ("Content-Type".equalsIgnoreCase(name) && value != null) {
                String charset = StringUtils.substringAfter(value, "charset=");
                if (charset != null) {
                    encoding = charset;
                }
                break;
            }
        }

        return encoding;
    }
}
