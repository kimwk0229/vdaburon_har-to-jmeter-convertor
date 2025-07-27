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

// HAR parser
import de.sstoehr.harreader.model.Har;
import de.sstoehr.harreader.model.HarEntry;
import de.sstoehr.harreader.model.HarHeader;
import de.sstoehr.harreader.model.HarPage;
import de.sstoehr.harreader.model.HarPostData;
import de.sstoehr.harreader.model.HarPostDataParam;
import de.sstoehr.harreader.model.HarQueryParam;
import de.sstoehr.harreader.model.HarRequest;
import de.sstoehr.harreader.model.HttpMethod;

import io.github.vdaburon.jmeter.har.common.TransactionInfo;
import io.github.vdaburon.jmeter.har.lrwr.ManageLrwr;
import io.github.vdaburon.jmeter.har.websocket.WebSocketPDoornboschXmlJmx;
import io.github.vdaburon.jmeter.har.websocket.WebSocketRequest;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class create a JMeter script jmx file from a HAR JSON file.
 * The jmx (JMeter Xml) file is a XML file, we use XML library to create the file.
 */
// 이 클래스는 HAR JSON 파일로부터 JMeter 스크립트 JMX 파일을 생성합니다.
// JMX (JMeter XML) 파일은 XML 파일이므로, XML 라이브러리를 사용하여 파일을 생성합니다.


public class XmlJmx {

    protected static final String K_SCHEME = "scheme";
    protected static final String K_HOST = "host";
    protected static final String K_PORT = "port";
    private static final String K_JMETER_VERSION = "5.6.3";
    private static final String K_THREAD_GROUP_NAME = "Thead Group HAR Imported";
    private static final String K_VIEW_RESULT_TREE_COMMENT = "For The Recording XML File Created";
    private static final Logger LOGGER = Logger.getLogger(XmlJmx.class.getName());

    protected Document convertHarToJmxXml(Har har, long createNewTransactionAfterRequestMs, boolean isAddPause, boolean isRemoveCookie, boolean isRemoveCacheRequest, String urlFilterToInclude, String urlFilterToExclude, int pageStartNumber, int samplerStartNumber, List<TransactionInfo> listTransactionInfo, boolean isAddViewTreeForRecord, WebSocketRequest webSocketRequest, String recordXmlOut) throws ParserConfigurationException, URISyntaxException {

        // URL 포함 필터가 비어 있지 않으면 해당 패턴을 컴파일합니다.
        Pattern patternUrlInclude = null;
        if (!urlFilterToInclude.isEmpty()) {
            patternUrlInclude = Pattern.compile(urlFilterToInclude);
        }

        // URL 제외 필터가 비어 있지 않으면 해당 패턴을 컴파일합니다.
        Pattern patternUrlExclude = null;
        if (!urlFilterToExclude.isEmpty()) {
            patternUrlExclude = Pattern.compile(urlFilterToExclude);
        }

        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
        Document document = documentBuilder.newDocument();

        // JMeter 테스트 계획 및 스레드 그룹 생성
        Element eltHashTreeAfterTestPlan = createJmxTestPlanAndTheadGroup(document);
        Element hashAfterThreadGroup = createHashTree(document);
        eltHashTreeAfterTestPlan.appendChild(hashAfterThreadGroup);
        // 생성된 테스트 계획 해시 트리에 스레드 그룹 해시 트리를 추가합니다.

        // 기록을 위한 뷰 트리 추가 여부 확인
        // 뷰 트리를 추가하고 기록 XML 출력이 비어 있지 않으면
        // HTTP 테스트 스크립트 레코더와 뷰 결과 트리를 생성하여 추가합니다.
        // isAddViewTreeForRecord가 true이고 recordXmlOut이 비어 있지 않으면 기록을 위한 뷰 트리를 생성합니다.
        if (isAddViewTreeForRecord && !recordXmlOut.isEmpty()) {
            createTestScriptRecoderAndViewResultTree(recordXmlOut, document, eltHashTreeAfterTestPlan);
        }

        // HAR에서 첫 페이지 또는 URL의 스키마, 호스트, 포트 정보 가져오기
        HashMap<String, String> hSchemeHostPort = getSchemeHostPortFirstPageOrUrl(har);
        String scheme = hSchemeHostPort.get(K_SCHEME);
        String host = hSchemeHostPort.get(K_HOST);
        String sPort = hSchemeHostPort.get(K_PORT);
        int iPort = Integer.parseInt(sPort);

        // 사용자 정의 변수 생성 및 추가
        Element eltUdv = createUserDefinedVariable(document, hSchemeHostPort);
        // 사용자 정의 변수 요소를 생성하고 스레드 그룹 해시 트리에 추가합니다.
        hashAfterThreadGroup.appendChild(eltUdv);
        Element hashTreeEmpty1 = createHashTree(document);
        hashAfterThreadGroup.appendChild(hashTreeEmpty1);
        // 빈 해시 트리를 생성하고 사용자 정의 변수 뒤에 추가합니다.

        // 구성 테스트 요소 생성 및 추가
        Element configTestElement = createConfigTestElement(document);
        // 구성 테스트 요소를 생성하고 스레드 그룹 해시 트리에 추가합니다.
        hashAfterThreadGroup.appendChild(configTestElement);
        Element hashTreeEmpty2 = createHashTree(document);
        hashAfterThreadGroup.appendChild(hashTreeEmpty2);
        // 빈 해시 트리를 생성하고 구성 테스트 요소 뒤에 추가합니다.

        // 쿠키 관리자 생성 및 추가
        Element eltCookieManger = createCookieManager(document);
        // 쿠키 관리자 요소를 생성하고 스레드 그룹 해시 트리에 추가합니다.
        hashAfterThreadGroup.appendChild(eltCookieManger);
        Element hashTreeEmpty3 = createHashTree(document);
        hashAfterThreadGroup.appendChild(hashTreeEmpty3);
        // 빈 해시 트리를 생성하고 쿠키 관리자 뒤에 추가합니다.

        // 캐시 관리자 생성 및 추가
        Element eltCacheManager = createCacheManager(document);
        hashAfterThreadGroup.appendChild(eltCacheManager);
        Element hashTreeEmpty4 = createHashTree(document);
        hashAfterThreadGroup.appendChild(hashTreeEmpty4);
        // 빈 해시 트리를 생성하고 캐시 관리자 뒤에 추가합니다.

        // HAR 로그에서 페이지 목록 가져오기
        List<HarPage> lPages = har.getLog().getPages();
        // 페이지 수 로깅
        if (lPages != null) {
            LOGGER.info("Number of page(s) in the HAR : " + lPages.size());
        }

        // 페이지가 없거나 비어 있으면 첫 번째 엔트리에서 페이지를 생성합니다.
        // 엔트리도 없으면 예외를 발생시킵니다.
        // HAR에 페이지가 없으면 첫 번째 엔트리에서 가상의 페이지를 생성합니다.
        boolean isNoPage = false;

        if (lPages == null || (lPages != null && lPages.size() == 0)) {
            lPages = createOneHarPage(har);
            isNoPage = true;
        }

        // 시간 및 트랜잭션 관련 변수 초기화
        long timePageBefore = 0;
        long timeRequestBefore = 0;

        boolean isCreateNewTransactionAfterRequestMs = false;
        if (createNewTransactionAfterRequestMs > 0 && lPages.size() == 1) {
            // 단일 페이지에서 요청 간 시간 기준으로 새 트랜잭션을 생성할지 여부를 결정합니다.
            // 요청 후 새 트랜잭션 생성 조건 설정
            // createNewTransactionAfterRequestMs가 0보다 크고 페이지 수가 1이면 새 트랜잭션을 생성합니다.
            isCreateNewTransactionAfterRequestMs = true;
        }

        int pageNum = pageStartNumber;
        int httpSamplernum = samplerStartNumber;

        for (int p = 0; p < lPages.size(); p++) {
            HarPage pageInter = lPages.get(p);
            // 현재 HAR 페이지를 가져옵니다.
            String pageId = pageInter.getId();
            String pageTitle = "";
            try {
                URI pageUrl = new URI(pageInter.getTitle());
                pageTitle = pageUrl.getPath();
            } catch (java.net.URISyntaxException ex) {
                // the title is not a valid uri, use directly the title
                pageTitle = pageInter.getTitle();
            }

            // 트랜잭션 정보 처리
            TransactionInfo transactionInfo = null;
            if (listTransactionInfo != null) {
                // 외부 트랜잭션 정보가 제공된 경우 해당 정보를 사용하여 페이지 제목을 설정합니다.
                // Do we have a page  from lrwr Transaction or external cv file transaction info ?
                Date datePageStartedDateTime = pageInter.getStartedDateTime();
                String pageStartedDateTime = Utils.dateToIsoFormat(datePageStartedDateTime);

                transactionInfo = ManageLrwr.getTransactionInfoAroundDateTime(pageStartedDateTime, listTransactionInfo);
                if (transactionInfo != null) {
                    pageTitle = transactionInfo.getName();
                    LOGGER.info("Set the page title with the transaction name: " + pageTitle);
                }
            }

            // 트랜잭션 컨트롤러 이름 설정
            String tcName = String.format("PAGE_%02d - " + pageTitle, pageNum); // PAGE_03 - /gestdocqualif/servletStat
            // 트랜잭션 컨트롤러 이름을 형식화합니다.
            pageNum++;

            if (p == 0) {
                // first page
                // 첫 번째 페이지의 시작 시간을 기록합니다.
                timePageBefore = pageInter.getStartedDateTime().getTime();
            } else {
                // 페이지 간 시간 계산 및 일시 정지 추가
                long timeBetween2Pages = pageInter.getStartedDateTime().getTime() - timePageBefore;

                if (isAddPause && timeBetween2Pages > 0) {
                    createTestActionPauseAndTree(document, timeBetween2Pages, hashAfterThreadGroup);
                    // 페이지 간 일시 정지를 추가합니다.
                }
                // 다음 페이지를 위한 시간 업데이트
                timePageBefore = pageInter.getStartedDateTime().getTime();
            }

            // 트랜잭션 컨트롤러를 생성하고 스레드 그룹 해시 트리에 추가합니다.
            Element hashTreeAfterTc = createTranControlAndTree(document, tcName, hashAfterThreadGroup);

            List<HarEntry> lEntries = har.getLog().getEntries();
            String currentUrl = "";

            // 각 HAR 엔트리를 반복 처리합니다.
            for (int e = 0; e < lEntries.size(); e++) {
                // 각 HAR 엔트리 처리
                HarEntry harEntryInter = lEntries.get(e);
                if (e == 0) {
                    timeRequestBefore = harEntryInter.getStartedDateTime().getTime();
                }

                // 요청 시작 시간 및 요청 간 시간 계산
                long timeRequestStarted = harEntryInter.getStartedDateTime().getTime();
                long timeBetween2Requests = timeRequestStarted - timeRequestBefore;

                String pageref = harEntryInter.getPageref();
                // 엔트리의 페이지 참조를 가져옵니다.
                if ((pageref != null && pageref.equals(pageId)) || isNoPage) {
                    // 페이지 참조가 일치하거나 페이지가 없는 경우 요청 처리
                    // 현재 엔트리가 현재 페이지에 속하거나 페이지가 없는 경우 처리합니다.
                    HarRequest harRequest = harEntryInter.getRequest();
                    currentUrl = harRequest.getUrl();

                    boolean isAddThisRequest = true;

                    if (patternUrlInclude != null) {  // 첫 번째 URL 필터 포함
                        // URL 포함 필터가 있는 경우 요청 URL이 패턴과 일치하는지 확인합니다.
                        Matcher matcher = patternUrlInclude.matcher(currentUrl);
                        isAddThisRequest = matcher.find();
                    }

                    if (isAddThisRequest && patternUrlExclude != null) {  // 두 번째 URL 필터 제외
                        Matcher matcher = patternUrlExclude.matcher(currentUrl);
                        // URL 제외 필터가 있는 경우 요청 URL이 패턴과 일치하지 않는지 확인합니다.
                        isAddThisRequest = !matcher.find();
                    }

                    HashMap hAddictional = (HashMap<String, Object>) harEntryInter.getAdditional();
                    if (isAddThisRequest && hAddictional != null) {  // 캐시된 요청 필터링
                        String fromCache = (String) hAddictional.get("_fromCache");
                        // 요청이 캐시된 경우 추가하지 않습니다.
                        if (fromCache != null) {
                            // this url content is in the browser cache (memory or disk) no need to create a new request
                            isAddThisRequest = false;
                        }
                    }

                    if (isAddThisRequest) {  // 요청 추가가 허용된 경우 처리
                        URI url = new URI(harRequest.getUrl());
                        // 요청 URL을 파싱합니다.
                        String samplerLabel = String.format("%03d " + url.getPath(), httpSamplernum); // 003 /gestdocqualif/servletStat
                        httpSamplernum++;
                        String sUrl = harRequest.getUrl();
                        String startUrl = sUrl.substring(0, Math.min(2, sUrl.length())); // ht or ws

                        // "data:" 프로토콜 스킵
                        // "data:" 프로토콜로 시작하는 URL은 JMeter에서 지원되지 않으므로 건너뜁니다.
                        if ("da".equalsIgnoreCase(startUrl)) { //data
                            // jmeter는 data:image 프로토콜을 지원하지 않습니다.
                            continue;
                        }

                        Element sampler = null;
                        boolean isWebSocket = false;
                        if ("ws".equalsIgnoreCase(startUrl) && webSocketRequest != null) { // ws or wss
                            // WebSocket 요청인 경우 WebSocket 샘플러를 생성합니다.
                            URI pageUrlFromRequest = new URI(harRequest.getUrl());
                            String tcNameFromRequest = String.format("PAGE_%02d - WebSocket " + pageUrlFromRequest.getPath(), pageNum); // PAGE_03 - /gestdocqualif/servletStat
                            pageNum++;
                            Element eltTransactionControllerNew = createTransactionController(document, tcNameFromRequest);
                            hashTreeAfterTc = createHashTree(document);
                            httpSamplernum = WebSocketPDoornboschXmlJmx.createWebSocketPDoornboschTree(document, hashTreeAfterTc, samplerLabel, scheme, host, iPort, httpSamplernum, webSocketRequest);
                            httpSamplernum++;
                            hashAfterThreadGroup.appendChild(eltTransactionControllerNew);
                            hashAfterThreadGroup.appendChild(hashTreeAfterTc);

                            continue; // 웹소켓 및 메시지가 추가되었으므로 이 샘플러에 대한 처리를 마칩니다.

                        } else {
                            sampler = createHttpSamplerProxy(document, samplerLabel, scheme, host, iPort, harRequest);
                        }
                        // HTTP 샘플러 프록시를 생성합니다.

                        // 트랜잭션 정보로부터 새 TC 생성 여부 확인
                        boolean isCreateNewTcFromTransactionInfo = false;
                        if (listTransactionInfo != null) {
                            // Do we have a page or sub page from lrwr Transaction or external cv file transaction info ?
                            Date dateEntryStartedDateTime = harEntryInter.getStartedDateTime(); // 현재 엔트리의 시작 시간
                            String entryStartedDateTime = Utils.dateToIsoFormat(dateEntryStartedDateTime); // ISO 형식으로 변환

                            TransactionInfo transactionInfo2 = ManageLrwr.getTransactionInfoAroundDateTime(entryStartedDateTime, listTransactionInfo); // 해당 시간 주변의 트랜잭션 정보 가져오기
                            if (transactionInfo2 != null) {
                                // 현재 엔트리 시간 주변에 트랜잭션 정보가 있는 경우 새 트랜잭션 컨트롤러를 생성합니다.
                                isCreateNewTcFromTransactionInfo = true; // 새 트랜잭션 컨트롤러 생성 필요

                                // 동일한 시작 타임스탬프를 가진 동일한 트랜잭션인 경우 아무것도 하지 않습니다.
                                if (transactionInfo != null) { // 기존 트랜잭션 정보가 있는 경우
                                    if (transactionInfo2.getBeginDateTime().equals(transactionInfo.getBeginDateTime())) {
                                        isCreateNewTcFromTransactionInfo = false; // 시작 타임스탬프가 같으면 동일 트랜잭션으로 간주하여 새 TC 생성 안 함
                                    } else {
                                        isCreateNewTcFromTransactionInfo = true;
                                    }
                                }

                                if (isCreateNewTcFromTransactionInfo) {
                                    pageTitle = transactionInfo2.getName();
                                    LOGGER.info("Set the page title with the transaction name: " + pageTitle); // 페이지 제목을 트랜잭션 이름으로 설정

                                    String tcNameFromRequest = String.format("PAGE_%02d - " + pageTitle, pageNum); // 새 트랜잭션 컨트롤러 이름 생성
                                    transactionInfo = transactionInfo2; // 현재 트랜잭션 정보를 업데이트
                                    pageNum++; // 페이지 번호 증가

                                    hashTreeAfterTc = createTranControlAndTree(document, tcNameFromRequest, hashAfterThreadGroup);
                                }
                            }
                        }

                        // 요청 간 시간 기준으로 새 트랜잭션 생성
                        if (isCreateNewTransactionAfterRequestMs && timeBetween2Requests > createNewTransactionAfterRequestMs) {
                            // 요청 간 시간이 설정된 임계값을 초과하면 새 트랜잭션 컨트롤러를 생성합니다.
                            if (isAddPause) { // 일시 정지 추가 여부
                                createTestActionPauseAndTree(document, timeBetween2Requests, hashAfterThreadGroup);
                            }

                            if (!isCreateNewTcFromTransactionInfo) { // 트랜잭션 정보로부터 새 TC가 생성되지 않은 경우
                                URI pageUrlFromRequest = new URI(harRequest.getUrl()); // 요청 URL로부터 URI 생성
                                String tcNameFromRequest = String.format("PAGE_%02d - " + pageUrlFromRequest.getPath(), pageNum); // 새 트랜잭션 컨트롤러 이름 생성
                                pageNum++; // 페이지 번호 증가
                                hashTreeAfterTc = createTranControlAndTree(document, tcNameFromRequest, hashAfterThreadGroup);
                            }
                        }
                        // 요청 시간 업데이트
                        timeRequestBefore = timeRequestStarted;
                        // 다음 요청을 위해 현재 요청 시간을 업데이트합니다.

                        hashTreeAfterTc.appendChild(sampler);
                        Element hashTreeAfterHttpSampler = createHashTree(document); // HTTP 샘플러 뒤에 해시 트리 생성
                        hashTreeAfterTc.appendChild(hashTreeAfterHttpSampler); // HTTP 샘플러 뒤에 해시 트리 추가

                        // 헤더 관리자를 생성하고 HTTP 샘플러 뒤에 추가합니다.
                        createHeaderManagerAndTree(isRemoveCookie, isRemoveCacheRequest, document, harRequest, hashTreeAfterHttpSampler);
                    } else {
                        // isAddThisRequest == false  // 요청이 필터링된 경우 로깅
                        LOGGER.fine("This url is filtred : " + currentUrl);
                    }
                }
            }
        }
        // 생성된 HTTP 샘플러 프록시의 총 개수를 로깅합니다.
        LOGGER.info("JMX file contains " + httpSamplernum + " HTTPSamplerProxy");

        return document;
    }
    // HAR 데이터를 기반으로 JMX XML 문서를 생성하는 메서드입니다.

    private void createHeaderManagerAndTree(boolean isRemoveCookie, boolean isRemoveCacheRequest, Document document, HarRequest harRequest, Element hashTreeAfterHttpSampler) {
        Element headers = createHeaderManager(document, harRequest, isRemoveCookie, isRemoveCacheRequest); // 헤더 관리자 생성
        hashTreeAfterHttpSampler.appendChild(headers);
        Element hashTreeAfterHeaders = createHashTree(document);
        hashTreeAfterHttpSampler.appendChild(hashTreeAfterHeaders);
    }

//    private Element createTranControlAndTree3(Document document, String tcNameFromRequest, Element hashAfterThreadGroup) {
//        Element hashTreeAfterTc;
//        Element eltTransactionControllerNew = createTransactionController(document, tcNameFromRequest); // 새 트랜잭션 컨트롤러 생성
//        hashAfterThreadGroup.appendChild(eltTransactionControllerNew);
//        hashTreeAfterTc = createHashTree(document);
//        hashAfterThreadGroup.appendChild(hashTreeAfterTc);
//        return hashTreeAfterTc;
//    }
//
//    private Element createTranControlAndTree2(Document document, String tcNameFromRequest, Element hashAfterThreadGroup) {
//        Element hashTreeAfterTc;
//        Element eltTransactionControllerNew = createTransactionController(document, tcNameFromRequest); // 새 트랜잭션 컨트롤러 생성
//        hashAfterThreadGroup.appendChild(eltTransactionControllerNew); // 스레드 그룹에 추가
//        hashTreeAfterTc = createHashTree(document);
//        hashAfterThreadGroup.appendChild(hashTreeAfterTc);
//        return hashTreeAfterTc;
//    }

    private Element createTranControlAndTree(Document document, String tcName, Element hashAfterThreadGroup) {
        Element eltTransactionController = createTransactionController(document, tcName);
        hashAfterThreadGroup.appendChild(eltTransactionController);
        Element hashTreeAfterTc = createHashTree(document);
        // 트랜잭션 컨트롤러를 생성하고 스레드 그룹 해시 트리에 추가합니다.
        hashAfterThreadGroup.appendChild(hashTreeAfterTc);

        return hashTreeAfterTc;
    }

    private void createTestActionPauseAndTree(Document document, long timeBetween2Pages, Element hashAfterThreadGroup) {
        Element eltTestAction = createTestActionPause(document, "Flow Control Action PAUSE", timeBetween2Pages);
        hashAfterThreadGroup.appendChild(eltTestAction);
        // 일시 정지 액션을 생성하고 스레드 그룹 해시 트리에 추가합니다.
        Element hashAfterTestAction = createHashTree(document);
        hashAfterThreadGroup.appendChild(hashAfterTestAction);
    }

    private static List<HarPage> createOneHarPage(Har har) {
        List<HarPage> lPages;
        // no page, need to add one from first entry
        lPages = new ArrayList<HarPage>();
        // 새 HAR 페이지 목록을 초기화합니다.
        HarPage harPage = new HarPage();
        harPage.setId("PAGE_00");

        List<HarEntry> lEntries = har.getLog().getEntries();
        if (lEntries != null && lEntries.size() > 0) {
            HarEntry harEntryInter = lEntries.get(0);
            harPage.setTitle(harEntryInter.getRequest().getUrl());
            harPage.setStartedDateTime(harEntryInter.getStartedDateTime());
        } else {
            throw new InvalidParameterException("No Page and No Entry, can't convert this har file");
            // 페이지나 엔트리가 없으면 예외를 발생시킵니다.
        }

        lPages.add(harPage);

        return lPages;
    }

    private void createTestScriptRecoderAndViewResultTree(String recordXmlOut, Document document, Element eltHashTreeAfterTestPlan) {
        // HTTP 테스트 스크립트 레코더와 뷰 결과 트리를 생성하여 추가합니다.
        Element eltHttpTestScriptRecorder = createHttpTestScriptRecorder(document);
        // HTTP 테스트 스크립트 레코더를 생성하고 테스트 계획 해시 트리에 추가합니다.
        eltHashTreeAfterTestPlan.appendChild(eltHttpTestScriptRecorder);

        Element hashTreeAfterTestScriptRecorder = createHashTree(document);
        Element eltViewResultTree = createViewResultTree(document, recordXmlOut);
        hashTreeAfterTestScriptRecorder.appendChild(eltViewResultTree);
        // 뷰 결과 트리를 생성하고 테스트 스크립트 레코더 해시 트리에 추가합니다.

        Element hashTreeAfterViewResultTree = createHashTree(document);
        hashTreeAfterTestScriptRecorder.appendChild(hashTreeAfterViewResultTree);
        eltHashTreeAfterTestPlan.appendChild(hashTreeAfterTestScriptRecorder);
    }

    protected Element createHttpTestScriptRecorder(Document document) {
        /*
        <ProxyControl guiclass="ProxyControlGui" testclass="ProxyControl" testname="HTTP(S) Test Script Recorder" enabled="false">
           1 <stringProp name="ProxyControlGui.port">8888</stringProp>
           2 <collectionProp name="ProxyControlGui.exclude_list"/>
           3 <collectionProp name="ProxyControlGui.include_list"/>
           4 <boolProp name="ProxyControlGui.capture_http_headers">true</boolProp>
           5 <intProp name="ProxyControlGui.grouping_mode">0</intProp>
           6 <boolProp name="ProxyControlGui.add_assertion">false</boolProp>
           7 <stringProp name="ProxyControlGui.sampler_type_name"></stringProp>
           8 <boolProp name="ProxyControlGui.sampler_redirect_automatically">false</boolProp>
           9 <boolProp name="ProxyControlGui.sampler_follow_redirects">true</boolProp>
          10 <boolProp name="ProxyControlGui.use_keepalive">true</boolProp>
          11 <boolProp name="ProxyControlGui.detect_graphql_request">true</boolProp>
          12 <boolProp name="ProxyControlGui.sampler_download_images">false</boolProp>
          13 <intProp name="ProxyControlGui.proxy_http_sampler_naming_mode">0</intProp>
          14 <stringProp name="ProxyControlGui.default_encoding"></stringProp>
          15 <stringProp name="ProxyControlGui.proxy_prefix_http_sampler_name"></stringProp>
          16 <stringProp name="ProxyControlGui.proxy_pause_http_sampler"></stringProp>
          17 <boolProp name="ProxyControlGui.notify_child_sl_filtered">false</boolProp>
          18 <boolProp name="ProxyControlGui.regex_match">false</boolProp>
          19 <stringProp name="ProxyControlGui.content_type_include"></stringProp>
          20 <stringProp name="ProxyControlGui.content_type_exclude"></stringProp>
      </ProxyControl>
      // 프록시 컨트롤 요소 생성
      // HTTP(S) 테스트 스크립트 레코더 요소를 생성합니다.
      <hashTree>
         */
        Element eltProxyControl = document.createElement("ProxyControl");
        Attr attrPcguiclass = document.createAttribute("guiclass");
        attrPcguiclass.setValue("ProxyControlGui");
        eltProxyControl.setAttributeNode(attrPcguiclass);

        Attr attrPctestclass = document.createAttribute("testclass");
        attrPctestclass.setValue("ProxyControl");
        eltProxyControl.setAttributeNode(attrPctestclass);

        Attr attrPctestname = document.createAttribute("testname");
        attrPctestname.setValue("HTTP(S) Test Script Recorder");
        eltProxyControl.setAttributeNode(attrPctestname);

        Attr attrPcenabled = document.createAttribute("enabled");
        attrPcenabled.setValue("false");
        eltProxyControl.setAttributeNode(attrPcenabled);
        // 프록시 컨트롤 요소의 속성을 설정합니다.
        // 프록시 컨트롤 속성 설정

        Element stringProp1 = createProperty(document, "stringProp", "ProxyControlGui.port", "8888");
        eltProxyControl.appendChild(stringProp1);
        Element collectionProp2 = createProperty(document, "collectionProp", "ProxyControlGui.exclude_list", null);
        eltProxyControl.appendChild(collectionProp2);
        Element collectionProp3 = createProperty(document, "collectionProp", "ProxyControlGui.include_list", null);
        eltProxyControl.appendChild(collectionProp3);
        Element boolProp4 = createProperty(document, "boolProp", "ProxyControlGui.capture_http_headers", "true");
        eltProxyControl.appendChild(boolProp4);
        Element intProp5 = createProperty(document, "boolProp", "ProxyControlGui.grouping_mode", "0");
        eltProxyControl.appendChild(intProp5);
        Element boolProp6 = createProperty(document, "boolProp", "ProxyControlGui.add_assertion", "false");
        eltProxyControl.appendChild(boolProp6);
        Element stringProp7 = createProperty(document, "stringProp", "ProxyControlGui.sampler_type_name", null);
        eltProxyControl.appendChild(stringProp7);
        Element boolProp8 = createProperty(document, "boolProp", "ProxyControlGui.sampler_redirect_automatically", "false");
        eltProxyControl.appendChild(boolProp8);
        Element boolProp9 = createProperty(document, "boolProp", "ProxyControlGui.sampler_follow_redirects", "true");
        eltProxyControl.appendChild(boolProp9);
        Element boolProp10 = createProperty(document, "boolProp", "ProxyControlGui.use_keepalive", "true");
        eltProxyControl.appendChild(boolProp10);
        Element boolProp11 = createProperty(document, "boolProp", "ProxyControlGui.detect_graphql_request", "true");
        eltProxyControl.appendChild(boolProp11);
        Element boolProp12 = createProperty(document, "boolProp", "ProxyControlGui.sampler_download_images", "false");
        eltProxyControl.appendChild(boolProp12);
        Element stringProp13 = createProperty(document, "intProp", "ProxyControlGui.proxy_http_sampler_naming_mode", "0");
        eltProxyControl.appendChild(stringProp13);
        Element stringProp14 = createProperty(document, "stringProp", "ProxyControlGui.default_encoding", null);
        eltProxyControl.appendChild(stringProp14);
        Element stringProp15 = createProperty(document, "stringProp", "ProxyControlGui.proxy_prefix_http_sampler_name", null);
        eltProxyControl.appendChild(stringProp15);
        Element stringProp16 = createProperty(document, "stringProp", "ProxyControlGui.proxy_pause_http_sampler", null);
        eltProxyControl.appendChild(stringProp16);
        Element boolProp17 = createProperty(document, "boolProp", "ProxyControlGui.notify_child_sl_filtered", "false");
        eltProxyControl.appendChild(boolProp17);
        Element boolProp18 = createProperty(document, "boolProp", "ProxyControlGui.regex_match", "false");
        eltProxyControl.appendChild(boolProp18);
        Element stringProp19 = createProperty(document, "stringProp", "ProxyControlGui.content_type_include", null);
        eltProxyControl.appendChild(stringProp19);
        Element stringProp20 = createProperty(document, "stringProp", "ProxyControlGui.content_type_exclude", null);
        eltProxyControl.appendChild(stringProp20);

        return eltProxyControl;
    }

    protected Element createViewResultTree(Document document, String recordXmlOut) {
        /*
        <ResultCollector guiclass="ViewResultsFullVisualizer" testclass="ResultCollector" testname="View Results Tree">
          <boolProp name="ResultCollector.error_logging">false</boolProp>
          <objProp>
            <name>saveConfig</name>
            <value class="SampleSaveConfiguration">
              <time>true</time>
              <latency>true</latency>
              <timestamp>true</timestamp>
              <success>true</success>
              <label>true</label>
              <code>true</code>
              <message>true</message>
              <threadName>true</threadName>
              <dataType>true</dataType>
              <encoding>false</encoding>
              <assertions>true</assertions>
              <subresults>true</subresults>
              <responseData>false</responseData>
              <samplerData>false</samplerData>
              <xml>false</xml>
              <fieldNames>true</fieldNames>
              <responseHeaders>false</responseHeaders>
              <requestHeaders>false</requestHeaders>
              <responseDataOnError>false</responseDataOnError>
              <saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
              <assertionsResultsToSave>0</assertionsResultsToSave>
              <bytes>true</bytes>
              <sentBytes>true</sentBytes>
              <url>true</url>
              <hostname>true</hostname>
              <threadCounts>true</threadCounts>
              <idleTime>true</idleTime>
              <connectTime>true</connectTime>
            </value>
          </objProp>
          <stringProp name="filename">C:/jmeter/har/record.xml</stringProp>
        </ResultCollector>
        <hashTree/>
        // 결과 수집기 요소 생성
       */
        // 뷰 결과 트리 요소를 생성합니다.
        Element eltResultCollector = document.createElement("ResultCollector");
        Attr attrRcguiclass = document.createAttribute("guiclass");
        attrRcguiclass.setValue("ViewResultsFullVisualizer");
        eltResultCollector.setAttributeNode(attrRcguiclass);

        Attr attrRctestclass = document.createAttribute("testclass");
        attrRctestclass.setValue("ResultCollector");
        eltResultCollector.setAttributeNode(attrRctestclass);

        Attr attrRctestname = document.createAttribute("testname");
        attrRctestname.setValue("View Results Tree");
        eltResultCollector.setAttributeNode(attrRctestname);
        // 결과 수집기 요소의 속성을 설정합니다.
        // 결과 수집기 속성 설정


        Element boolProp1 = createProperty(document, "boolProp", "ResultCollector.error_logging", "false");
        eltResultCollector.appendChild(boolProp1);
        Element stringProp2 = createProperty(document, "stringProp", "filename", recordXmlOut);
        eltResultCollector.appendChild(stringProp2);
        Element stringProp3 = createProperty(document, "stringProp", "TestPlan.comments", K_VIEW_RESULT_TREE_COMMENT);
        eltResultCollector.appendChild(stringProp3);


        return eltResultCollector;
    }

    protected Element createJmxTestPlanAndTheadGroup(Document document) {
/*

<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan" enabled="true">
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="HAR Imported" enabled="false">
        <stringProp name="ThreadGroup.num_threads">1</stringProp>
        <stringProp name="ThreadGroup.ramp_time">1</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" enabled="true">
          <stringProp name="LoopController.loops">1</stringProp>
          <boolProp name="LoopController.continue_forever">false</boolProp>
        </elementProp>
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <boolProp name="ThreadGroup.delayedStart">false</boolProp>
        <boolProp name="ThreadGroup.scheduler">false</boolProp>
        <stringProp name="ThreadGroup.duration"></stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>

 */
        // jmeterTestPlan 루트 요소 생성
        // JMX 테스트 계획의 루트 요소를 생성합니다.
        // <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.2">
        Element root = document.createElement("jmeterTestPlan");
        document.appendChild(root);
        Attr attrversion = document.createAttribute("version");
        attrversion.setValue("1.2");
        root.setAttributeNode(attrversion);

        Attr attrproperties = document.createAttribute("properties");
        attrproperties.setValue("5.0");
        root.setAttributeNode(attrproperties);

        Attr attrjmeter = document.createAttribute("jmeter");
        attrjmeter.setValue(K_JMETER_VERSION);
        root.setAttributeNode(attrjmeter);
        // jmeterTestPlan 요소의 속성을 설정합니다.
        // jmeterTestPlan 속성 설정

        //   <hashTree>
        Element eltRoothashTree = createHashTree(document);
        // 루트 해시 트리를 생성하고 jmeterTestPlan에 추가합니다.
        root.appendChild(eltRoothashTree);

        // <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan" enabled="true">
        Element eltTestPlan = document.createElement("TestPlan");
        // TestPlan 요소 생성
        Attr attrTpguiclass = document.createAttribute("guiclass");
        attrTpguiclass.setValue("TestPlanGui");
        eltTestPlan.setAttributeNode(attrTpguiclass);

        Attr attrTptestclass = document.createAttribute("testclass");
        attrTptestclass.setValue("TestPlanGui");
        eltTestPlan.setAttributeNode(attrTptestclass);

        Attr attrTptestname = document.createAttribute("testname");
        attrTptestname.setValue("Test Plan");
        eltTestPlan.setAttributeNode(attrTptestname);

        Attr attrTpenabled = document.createAttribute("enabled");
        attrTpenabled.setValue("true");
        eltTestPlan.setAttributeNode(attrTpenabled);
        // TestPlan 요소의 속성을 설정합니다.
        // TestPlan 속성 설정

        /*
       <stringProp name="TestPlan.comments">This test plan was created by io.github.vdaburon:har-to-jmeter-convertor v1.0</stringProp>
       <boolProp name="TestPlan.functional_mode">false</boolProp>
       <boolProp name="TestPlan.tearDown_on_shutdown">false</boolProp>
       <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>

         */
        // TestPlan의 boolProp 요소들을 생성하고 추가합니다.
        Element eltBoolProp1 = createProperty(document, "boolProp", "TestPlan.functional_mode", "false");
        eltTestPlan.appendChild(eltBoolProp1);

        Element eltBoolProp2 = createProperty(document, "boolProp", "TestPlan.tearDown_on_shutdown", "false");
        eltTestPlan.appendChild(eltBoolProp2);

        Element eltBoolProp3 = createProperty(document, "boolProp", "TestPlan.serialize_threadgroups", "false");
        eltTestPlan.appendChild(eltBoolProp3);


        /*
        <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
      */
        // TestPlan의 elementProp (사용자 정의 변수)를 생성하고 추가합니다.
        Element eltTpElementProp = createElementProp(document, "TestPlan.user_defined_variables", "Arguments", "ArgumentsPanel", "Arguments", "User Defined Variables");
        eltTestPlan.appendChild(eltTpElementProp);

        Element eltTpCollectionProp = document.createElement("collectionProp");
        Attr attrTpCollectionPropname = document.createAttribute("name");
        attrTpCollectionPropname.setValue("Arguments.arguments");
        eltTpCollectionProp.setAttributeNode(attrTpCollectionPropname);
        // Arguments.arguments collectionProp을 생성하고 elementProp에 추가합니다.

        eltTpElementProp.appendChild(eltTpCollectionProp);

        /*
        <stringProp name="TestPlan.comments"></stringProp>
      <stringProp name="TestPlan.user_define_classpath"></stringProp>
      // TestPlan의 stringProp (주석 및 클래스패스) 추가
         */
        // TestPlan의 stringProp (주석 및 클래스패스)를 생성하고 추가합니다.
        String versionComment = "This test plan was created by io.github.vdaburon:har-to-jmeter-convertor Version " + HarForJMeter.APPLICATION_VERSION;
        Element eltTpStringProp1 = createProperty(document, "stringProp", "TestPlan.comments", versionComment);
        eltTestPlan.appendChild(eltTpStringProp1);

        Element eltTpStringProp2 = createProperty(document, "stringProp", "TestPlan.user_define_classpath", null);
        eltTestPlan.appendChild(eltTpStringProp2);
        // TestPlan 요소에 자식 요소들을 추가합니다.

        eltRoothashTree.appendChild(eltTestPlan);
        // TestPlan을 루트 해시 트리에 추가합니다.

        Element eltHashTreeAfterTestPlan = createHashTree(document);
        eltRoothashTree.appendChild(eltHashTreeAfterTestPlan);

        Element eltThreadGroup = createThreadGroup(document, K_THREAD_GROUP_NAME);
        eltHashTreeAfterTestPlan.appendChild(eltThreadGroup);
        return eltHashTreeAfterTestPlan;
    }

    protected Element createThreadGroup(Document document, String groupName) {
    /*
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Thead Group HAR Imported" enabled="false">
        <stringProp name="ThreadGroup.num_threads">1</stringProp>
        <stringProp name="ThreadGroup.ramp_time">1</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" enabled="true">
          <stringProp name="LoopController.loops">1</stringProp>
          <boolProp name="LoopController.continue_forever">false</boolProp>
        </elementProp>
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <boolProp name="ThreadGroup.delayedStart">false</boolProp>
        <boolProp name="ThreadGroup.scheduler">false</boolProp>
        <stringProp name="ThreadGroup.duration"></stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>

     */
        // ThreadGroup 요소를 생성합니다.
        Element eltThreadGroup = document.createElement("ThreadGroup");
        Attr attrThreadGroupguiclass = document.createAttribute("guiclass");
        attrThreadGroupguiclass.setValue("ThreadGroupGui");
        eltThreadGroup.setAttributeNode(attrThreadGroupguiclass);

        Attr attrThreadGrouptestclass = document.createAttribute("testclass");
        attrThreadGrouptestclass.setValue("ThreadGroup");
        eltThreadGroup.setAttributeNode(attrThreadGrouptestclass);

        Attr attrThreadGrouptestname = document.createAttribute("testname");
        attrThreadGrouptestname.setValue(groupName);
        eltThreadGroup.setAttributeNode(attrThreadGrouptestname);

        Attr attrThreadGroupenabled = document.createAttribute("enabled");
        attrThreadGroupenabled.setValue("true");
        eltThreadGroup.setAttributeNode(attrThreadGroupenabled);
        // ThreadGroup 요소의 속성을 설정합니다.

        Element eltTgStringProp1 = createProperty(document, "stringProp", "ThreadGroup.num_threads", "1");
        eltThreadGroup.appendChild(eltTgStringProp1);
        // 스레드 그룹의 스레드 수를 설정합니다.

        Element eltTgStringProp2 = createProperty(document, "stringProp", "ThreadGroup.ramp_time", "1");
        eltThreadGroup.appendChild(eltTgStringProp2);
        // 스레드 그룹의 램프업 시간을 설정합니다.

        /*
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" enabled="true">
          <stringProp name="LoopController.loops">1</stringProp>
          <boolProp name="LoopController.continue_forever">false</boolProp>
        </elementProp>
        // ThreadGroup의 elementProp (메인 컨트롤러)를 생성하고 추가합니다.
         */
        Element eltTgElementProp = createElementProp(document, "ThreadGroup.main_controller", "LoopController", "LoopControlPanel", "LoopController", "");
        Element eltEltPropStringProp1 = createProperty(document, "stringProp", "LoopController.loops", "1");
        eltTgElementProp.appendChild(eltEltPropStringProp1);
        // 루프 컨트롤러의 루프 횟수를 설정합니다.

        Element eltEltPropBoolProp2 = createProperty(document, "boolProp", "LoopController.continue_forever", "false");
        eltTgElementProp.appendChild(eltEltPropBoolProp2);
        // 루프 컨트롤러의 무한 반복 여부를 설정합니다.

        eltThreadGroup.appendChild(eltTgElementProp);

        /*
         <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <boolProp name="ThreadGroup.delayedStart">false</boolProp>
        <boolProp name="ThreadGroup.scheduler">false</boolProp>
        <stringProp name="ThreadGroup.duration"></stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
        // ThreadGroup의 stringProp 및 boolProp 요소들을 생성하고 추가합니다.
         */
        Element eltTgStringProp3 = createProperty(document, "stringProp", "ThreadGroup.on_sample_error", "continue");
        eltThreadGroup.appendChild(eltTgStringProp3);
        // 샘플 오류 시 동작을 설정합니다.

        Element eltTgBoolProp4 = createProperty(document, "boolProp", "ThreadGroup.delayedStart", "false");
        eltThreadGroup.appendChild(eltTgBoolProp4);
        // 지연 시작 여부를 설정합니다.

        Element eltTgBoolProp5 = createProperty(document, "boolProp", "ThreadGroup.scheduler", "false");
        eltThreadGroup.appendChild(eltTgBoolProp5);
        // 스케줄러 사용 여부를 설정합니다.

        Element eltTgStringProp6 = createProperty(document, "stringProp", "ThreadGroup.duration", "");
        eltThreadGroup.appendChild(eltTgStringProp6);
        // 지속 시간을 설정합니다.

        Element eltTgStringProp7 = createProperty(document, "stringProp", "ThreadGroup.delay", "");
        eltThreadGroup.appendChild(eltTgStringProp7);
        // 지연 시간을 설정합니다.

        Element eltTgBoolProp8 = createProperty(document, "boolProp", "ThreadGroup.same_user_on_next_iteration", "true");
        eltThreadGroup.appendChild(eltTgBoolProp8);
        // 다음 반복에서 동일한 사용자 사용 여부를 설정합니다.

        return eltThreadGroup;
    }

    protected Element createCookieManager(Document document) {

        /*
        <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
          <collectionProp name="CookieManager.cookies"/>
          <boolProp name="CookieManager.clearEachIteration">true</boolProp>
          <boolProp name="CookieManager.controlledByThreadGroup">false</boolProp>
        </CookieManager>
         */
        // CookieManager 요소를 생성합니다.
        Element eltCookieManager = document.createElement("CookieManager");
        Attr attrCookieManagerguiclass = document.createAttribute("guiclass");
        attrCookieManagerguiclass.setValue("CookiePanel");
        eltCookieManager.setAttributeNode(attrCookieManagerguiclass);

        Attr attrCookieManagertestclass = document.createAttribute("testclass");
        attrCookieManagertestclass.setValue("CookieManager");
        eltCookieManager.setAttributeNode(attrCookieManagertestclass);

        Attr attrCookieManagertestname = document.createAttribute("testname");
        attrCookieManagertestname.setValue("HTTP Cookie Manager");
        eltCookieManager.setAttributeNode(attrCookieManagertestname);

        Attr attrCookieManagerenabled = document.createAttribute("enabled");
        attrCookieManagerenabled.setValue("true");
        eltCookieManager.setAttributeNode(attrCookieManagerenabled);
        // CookieManager 요소의 속성을 설정합니다.

        Element eltCollectionProp1 = createProperty(document, "collectionProp", "CookieManager.cookies", null);
        eltCookieManager.appendChild(eltCollectionProp1);
        Element eltBoolProp1 = createProperty(document, "boolProp", "CookieManager.clearEachIteration", "true");
        eltCookieManager.appendChild(eltBoolProp1);
        // 각 반복마다 쿠키를 지울지 여부를 설정합니다.

        Element eltBoolProp2 = createProperty(document, "boolProp", "CookieManager.controlledByThreadGroup", "false");
        eltCookieManager.appendChild(eltBoolProp2);

        return eltCookieManager;
    }

    protected Element createCacheManager(Document document) {
        /*
        <CacheManager guiclass="CacheManagerGui" testclass="CacheManager" testname="HTTP Cache Manager" enabled="true">
          <boolProp name="clearEachIteration">true</boolProp>
          <boolProp name="useExpires">true</boolProp>
          <boolProp name="CacheManager.controlledByThread">false</boolProp>
        </CacheManager>
        */
        // CacheManager 요소를 생성합니다.
        Element eltCacheManager = document.createElement("CacheManager");
        Attr attrCacheManagerguiclass = document.createAttribute("guiclass");
        attrCacheManagerguiclass.setValue("CacheManagerGui");
        eltCacheManager.setAttributeNode(attrCacheManagerguiclass);

        Attr attrCacheManagertestclass = document.createAttribute("testclass");
        attrCacheManagertestclass.setValue("CacheManager");
        eltCacheManager.setAttributeNode(attrCacheManagertestclass);

        Attr attrCacheManagertestname = document.createAttribute("testname");
        attrCacheManagertestname.setValue("HTTP Cache Manager");
        eltCacheManager.setAttributeNode(attrCacheManagertestname);

        Attr attrCacheManagerenabled = document.createAttribute("enabled");
        attrCacheManagerenabled.setValue("true");
        eltCacheManager.setAttributeNode(attrCacheManagerenabled);
        // CacheManager 요소의 속성을 설정합니다.

        Element eltBoolProp1 = createProperty(document, "boolProp", "clearEachIteration", "true");
        eltCacheManager.appendChild(eltBoolProp1);
        Element eltBoolProp2 = createProperty(document, "boolProp", "useExpires", "true");
        eltCacheManager.appendChild(eltBoolProp2);
        // 만료 시간을 사용할지 여부를 설정합니다.

        Element eltBoolProp3 = createProperty(document, "boolProp", "CacheManager.controlledByThread", "false");
        eltCacheManager.appendChild(eltBoolProp3);
        return eltCacheManager;
    }

    protected Element createTransactionController(Document document, String testname) {
        /*
        <TransactionController guiclass="TransactionControllerGui" testclass="TransactionController" testname="SC03_P01_ACCUEIL" enabled="true">
          <boolProp name="TransactionController.parent">false</boolProp>
          <boolProp name="TransactionController.includeTimers">false</boolProp>
        </TransactionController>
         */
        // TransactionController 요소를 생성합니다.
        Element eltTransactionController = document.createElement("TransactionController");
        Attr attrTransactionControllerguiclass = document.createAttribute("guiclass");
        attrTransactionControllerguiclass.setValue("TransactionControllerGui");
        eltTransactionController.setAttributeNode(attrTransactionControllerguiclass);

        Attr attrTransactionControllertestclass = document.createAttribute("testclass");
        attrTransactionControllertestclass.setValue("TransactionController");
        eltTransactionController.setAttributeNode(attrTransactionControllertestclass);

        Attr attrTransactionControllertestname = document.createAttribute("testname");
        attrTransactionControllertestname.setValue(testname);
        eltTransactionController.setAttributeNode(attrTransactionControllertestname);

        Attr attrTransactionControllerenabled = document.createAttribute("enabled");
        attrTransactionControllerenabled.setValue("true");
        eltTransactionController.setAttributeNode(attrTransactionControllerenabled);
        // TransactionController 요소의 속성을 설정합니다.

        Element eltBoolProp1 = createProperty(document, "boolProp", "TransactionController.parent", "false");
        eltTransactionController.appendChild(eltBoolProp1);
        Element eltBoolProp2 = createProperty(document, "boolProp", "TransactionController.includeTimers", "false");
        eltTransactionController.appendChild(eltBoolProp2);
        // 타이머 포함 여부를 설정합니다.

        return eltTransactionController;
    }

    public static Element createHashTree(Document document) {
        // hashTree 요소 생성
        Element eltHashTree = document.createElement("hashTree");
        return eltHashTree;
    }

    // 속성(stringProp, boolProp, intProp)을 생성하는 헬퍼 메서드
    public static Element createProperty(Document document, String elementProp, String parameterNameValue, String elementValue) {
        // 주어진 타입, 이름, 값으로 속성 요소를 생성합니다.
        Element eltProperty = document.createElement(elementProp); // boolProp or stringProp or intProp
        if (parameterNameValue != null) {
            Attr attrPropertyname = document.createAttribute("name");
            attrPropertyname.setValue(parameterNameValue);
            eltProperty.setAttributeNode(attrPropertyname);
        }

        if (elementValue != null) {
            eltProperty.setTextContent(elementValue);
        }
        return eltProperty;
    }

    // elementProp 요소를 생성하는 헬퍼 메서드
    protected Element createElementProp(Document document, String nameValue, String elementTypeValue, String guiClassValue, String testClassValue, String testNameValue) {
        // 주어진 이름, 요소 타입, GUI 클래스, 테스트 클래스, 테스트 이름으로 elementProp 요소를 생성합니다.
        Element eltElementProp = document.createElement("elementProp");
        Attr attrTpElementPropname = document.createAttribute("name");
        attrTpElementPropname.setValue(nameValue);
        eltElementProp.setAttributeNode(attrTpElementPropname);

        Attr attrTpElementPropelementType = document.createAttribute("elementType");
        attrTpElementPropelementType.setValue(elementTypeValue);
        eltElementProp.setAttributeNode(attrTpElementPropelementType);

        if (guiClassValue != null) {
            Attr attrTpElementPropguiclass = document.createAttribute("guiclass");
            attrTpElementPropguiclass.setValue(guiClassValue);
            eltElementProp.setAttributeNode(attrTpElementPropguiclass);
        }

        if (testClassValue != null) {
            Attr attrTpElementProptestclass = document.createAttribute("testclass");
            attrTpElementProptestclass.setValue(testClassValue);
            eltElementProp.setAttributeNode(attrTpElementProptestclass);
        }

        if (testNameValue != null) {
            Attr attrTpElementProptestname = document.createAttribute("testname");
            attrTpElementProptestname.setValue(testNameValue);
            eltElementProp.setAttributeNode(attrTpElementProptestname);
        }

        if (guiClassValue != null && testClassValue != null) {
            Attr attrTpElementPropenabled = document.createAttribute("enabled");
            attrTpElementPropenabled.setValue("true");
            eltElementProp.setAttributeNode(attrTpElementPropenabled);
        }
        return eltElementProp;
    }

    protected Element createUserDefinedVariable(Document document, HashMap <String, String> hSchemeHostPort) {
        /*
         <Arguments guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
          <collectionProp name="Arguments.arguments">
            <elementProp name="V_SCHEME" elementType="Argument">
              <stringProp name="Argument.name">V_SCHEME</stringProp>
              <stringProp name="Argument.value">http</stringProp>
              <stringProp name="Argument.metadata">=</stringProp>
            </elementProp>
            <elementProp name="V_HOST" elementType="Argument">
              <stringProp name="Argument.name">V_HOST</stringProp>
              <stringProp name="Argument.value">myhost</stringProp>
              <stringProp name="Argument.metadata">=</stringProp>
            </elementProp>
            <elementProp name="V_PORT" elementType="Argument">
              <stringProp name="Argument.name">V_PORT</stringProp>
              <stringProp name="Argument.value">8180</stringProp>
              <stringProp name="Argument.metadata">=</stringProp>
            </elementProp>
          </collectionProp>
        </Arguments>
         */
        // 사용자 정의 변수 (Arguments) 요소를 생성합니다.
        // Arguments 요소 생성 (User Defined Variables)
        Element eltArguments = document.createElement("Arguments");
        Attr attrArgumentsguiclass = document.createAttribute("guiclass");
        attrArgumentsguiclass.setValue("ArgumentsPanel");
        eltArguments.setAttributeNode(attrArgumentsguiclass);

        Attr attrArgumentstestclass = document.createAttribute("testclass");
        attrArgumentstestclass.setValue("Arguments");
        eltArguments.setAttributeNode(attrArgumentstestclass);

        Attr attrArgumentstestname = document.createAttribute("testname");
        attrArgumentstestname.setValue("User Defined Variables");
        eltArguments.setAttributeNode(attrArgumentstestname);

        Attr attrArgumentsenabled = document.createAttribute("enabled");
        attrArgumentsenabled.setValue("true");
        eltArguments.setAttributeNode(attrArgumentsenabled);
        // Arguments 요소의 속성을 설정합니다.

        Element eltCollectionProp = createProperty(document, "collectionProp", "Arguments.arguments", null);
        eltArguments.appendChild(eltCollectionProp);
        // Arguments.arguments collectionProp을 생성하고 Arguments 요소에 추가합니다.

        Element eltCollProElementProp1 = createElementProp(document, "V_SCHEME", "Argument", null, null, null);
        eltCollectionProp.appendChild(eltCollProElementProp1);
        Element eltCollProElementProp1StringProp1 = createProperty(document, "stringProp", "Argument.name", "V_SCHEME");
        eltCollProElementProp1.appendChild(eltCollProElementProp1StringProp1);
        // V_SCHEME 변수의 이름 속성을 설정합니다.

        String scheme = hSchemeHostPort.get(K_SCHEME);
        Element eltCollProElementProp1StringProp2 = createProperty(document, "stringProp", "Argument.value", scheme);
        eltCollProElementProp1.appendChild(eltCollProElementProp1StringProp2);
        // V_SCHEME 변수의 값 속성을 설정합니다.

        Element eltCollProElementProp1StringProp3 = createProperty(document, "stringProp", "Argument.metadata", "=");
        eltCollProElementProp1.appendChild(eltCollProElementProp1StringProp3);
        // V_SCHEME 변수의 메타데이터 속성을 설정합니다.

        Element eltCollProElementProp2 = createElementProp(document, "V_HOST", "Argument", null, null, null);
        eltCollectionProp.appendChild(eltCollProElementProp2);
        Element eltCollProElementProp2StringProp1 = createProperty(document, "stringProp", "Argument.name", "V_HOST");
        eltCollProElementProp2.appendChild(eltCollProElementProp2StringProp1);
        // V_HOST 변수의 이름 속성을 설정합니다.

        String host = hSchemeHostPort.get(K_HOST);
        Element eltCollProElementProp2StringProp2 = createProperty(document, "stringProp", "Argument.value", host);
        eltCollProElementProp2.appendChild(eltCollProElementProp2StringProp2);
        // V_HOST 변수의 값 속성을 설정합니다.

        Element eltCollProElementProp2StringProp3 = createProperty(document, "stringProp", "Argument.metadata", "=");
        eltCollProElementProp2.appendChild(eltCollProElementProp2StringProp3);
        // V_HOST 변수의 메타데이터 속성을 설정합니다.

        Element eltCollProElementProp3 = createElementProp(document, "V_PORT", "Argument", null, null, null);
        eltCollectionProp.appendChild(eltCollProElementProp3);

        String sPort = hSchemeHostPort.get(K_PORT);
        Element eltCollProElementProp3StringProp1 = createProperty(document, "stringProp", "Argument.name", "V_PORT");
        eltCollProElementProp3.appendChild(eltCollProElementProp3StringProp1);
        // V_PORT 변수의 이름 속성을 설정합니다.

        Element eltCollProElementProp3StringProp2 = createProperty(document, "stringProp", "Argument.value", sPort);
        eltCollProElementProp3.appendChild(eltCollProElementProp3StringProp2);

        Element eltCollProElementProp3StringProp3 = createProperty(document, "stringProp", "Argument.metadata", "=");
        eltCollProElementProp3.appendChild(eltCollProElementProp3StringProp3);

        return eltArguments;
    }

    protected Element createConfigTestElement(Document document) {
        /*
        <ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP Request Defaults" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>
          <stringProp name="HTTPSampler.domain">${V_HOST}</stringProp>      // 1
          <stringProp name="HTTPSampler.port">${V_PORT}</stringProp>        // 2
          <stringProp name="HTTPSampler.protocol">${V_SCHEME}</stringProp>  // 3
          <stringProp name="HTTPSampler.contentEncoding"></stringProp>      // 4
          <stringProp name="HTTPSampler.path"></stringProp>                 // 5
          <stringProp name="HTTPSampler.concurrentPool">6</stringProp>      // 6
          <stringProp name="HTTPSampler.connect_timeout"></stringProp>      // 7
          <stringProp name="HTTPSampler.response_timeout"></stringProp>     // 8
        </ConfigTestElement>
         */
        // ConfigTestElement 요소 생성 (HTTP Request Defaults)
        Element eltConfigTestElement = document.createElement("ConfigTestElement");
        Attr attrConfigTestElementguiclass = document.createAttribute("guiclass");
        attrConfigTestElementguiclass.setValue("HttpDefaultsGui");
        eltConfigTestElement.setAttributeNode(attrConfigTestElementguiclass);

        Attr attrConfigTestElementtestclass = document.createAttribute("testclass");
        attrConfigTestElementtestclass.setValue("ConfigTestElement");
        eltConfigTestElement.setAttributeNode(attrConfigTestElementtestclass);

        Attr attrConfigTestElementtestname = document.createAttribute("testname");
        attrConfigTestElementtestname.setValue("HTTP Request Defaults");
        eltConfigTestElement.setAttributeNode(attrConfigTestElementtestname);

        Attr attrConfigTestElementenabled = document.createAttribute("enabled");
        attrConfigTestElementenabled.setValue("true");
        eltConfigTestElement.setAttributeNode(attrConfigTestElementenabled);
        // ConfigTestElement 속성 설정

        Element eltConfigTestElementProp = createElementProp(document,"HTTPsampler.Arguments","Arguments","HTTPArgumentsPanel", "Arguments", "User Defined Variables" );
        eltConfigTestElement.appendChild(eltConfigTestElementProp);
        Element eltCollectionProp1 = createProperty(document, "collectionProp", "Arguments.arguments", null);
        eltConfigTestElement.appendChild(eltCollectionProp1);
        Element eltStringProp1 = createProperty(document, "stringProp", "HTTPSampler.domain", "${V_HOST}");
        eltConfigTestElement.appendChild(eltStringProp1);
        Element eltStringProp2 = createProperty(document, "stringProp", "HTTPSampler.port", "${V_PORT}");
        eltConfigTestElement.appendChild(eltStringProp2);
        Element eltStringProp3 = createProperty(document, "stringProp", "HTTPSampler.protocol", "${V_SCHEME}");
        eltConfigTestElement.appendChild(eltStringProp3);
        Element eltStringProp4 = createProperty(document, "stringProp", "HTTPSampler.contentEncoding", "");
        eltConfigTestElement.appendChild(eltStringProp4);
        Element eltStringProp5 = createProperty(document, "stringProp", "HTTPSampler.path", "");
        eltConfigTestElement.appendChild(eltStringProp5);
        Element eltStringProp6 = createProperty(document, "stringProp", "HTTPSampler.concurrentPool", "6");
        eltConfigTestElement.appendChild(eltStringProp6);
        Element eltStringProp7 = createProperty(document, "stringProp", "HTTPSampler.connect_timeout", "");
        eltConfigTestElement.appendChild(eltStringProp7);
        Element eltStringProp8 = createProperty(document, "stringProp", "HTTPSampler.response_timeout", "");
        eltConfigTestElement.appendChild(eltStringProp8);

        return eltConfigTestElement;
    }


    protected Element createHttpSamplerProxy(Document document, String testname, String scheme, String host, int iPort, HarRequest harRequest) throws URISyntaxException {
        /*
         <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="007 /gestdocqualif/servletLogin" enabled="true">
            <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" enabled="true">
         */
        // HTTPSamplerProxy 요소 생성
        Element eltHTTPSamplerProxy = document.createElement("HTTPSamplerProxy");
        Attr attrHTTPSamplerProxyguiclass = document.createAttribute("guiclass");
        attrHTTPSamplerProxyguiclass.setValue("HttpTestSampleGui");
        eltHTTPSamplerProxy.setAttributeNode(attrHTTPSamplerProxyguiclass);

        Attr attrHTTPSamplerProxytestclass = document.createAttribute("testclass");
        attrHTTPSamplerProxytestclass.setValue("HTTPSamplerProxy");
        eltHTTPSamplerProxy.setAttributeNode(attrHTTPSamplerProxytestclass);

        Attr attrHTTPSamplerProxytestname = document.createAttribute("testname");
        attrHTTPSamplerProxytestname.setValue(testname);
        eltHTTPSamplerProxy.setAttributeNode(attrHTTPSamplerProxytestname);

        Attr attrHTTPSamplerProxyenabled = document.createAttribute("enabled");
        attrHTTPSamplerProxyenabled.setValue("true");
        eltHTTPSamplerProxy.setAttributeNode(attrHTTPSamplerProxyenabled);
        // HTTPSamplerProxy 속성 설정

        HarPostData postData = harRequest.getPostData();
        String mimeType = postData.getMimeType();
        String doMultiPart = "false";
        if (mimeType != null && mimeType.contains("multipart/form-data")) {
            doMultiPart = "true";
            // multipart/form-data인 경우 DO_MULTIPART_POST를 true로 설정
        }
        // Element eltHTTPSamplerProxyElementProp = createElementProp(document, "HTTPsampler.Arguments", "Arguments", "HTTPArgumentsPanel", "Arguments", null);
        /*
            <stringProp name="HTTPSampler.domain"></stringProp>              // 1
            <stringProp name="HTTPSampler.port"></stringProp>                // 2
            <stringProp name="HTTPSampler.protocol">http</stringProp>        // 3
            <stringProp name="HTTPSampler.contentEncoding"></stringProp>     // 4
            <stringProp name="HTTPSampler.path">/gestdocqualif/</stringProp> // 5
            <stringProp name="HTTPSampler.method">GET</stringProp>           // 6
            <boolProp name="HTTPSampler.follow_redirects">true</boolProp>    // 7
            <boolProp name="HTTPSampler.auto_redirects">false</boolProp>     // 8
            <boolProp name="HTTPSampler.use_keepalive">true</boolProp>       // 9
            <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>  // 10
            <stringProp name="HTTPSampler.embedded_url_re"></stringProp>     // 11
            <stringProp name="HTTPSampler.connect_timeout"></stringProp>     // 12
            <stringProp name="HTTPSampler.response_timeout"></stringProp>    // 13
                */
        // URL 파싱
        URI url = new URI(harRequest.getUrl());

        String hostInter = "";
        if (!host.equalsIgnoreCase(url.getHost())) {  // 호스트가 다른 경우 설정
            hostInter = url.getHost();
        }
        Element stringProp1 = createProperty(document, "stringProp", "HTTPSampler.domain", hostInter);
        eltHTTPSamplerProxy.appendChild(stringProp1);

        int defautPort = 443;
        // 기본 포트 설정
        if ("http".equalsIgnoreCase(url.getScheme())) {
            defautPort = 80;
        }
        if ("ws".equalsIgnoreCase(url.getScheme())) {
            defautPort = 80;
        }
        // 포트가 다른 경우 설정

        String sPortInter = "";
        int port = url.getPort() == -1 ? defautPort : url.getPort();
        if (iPort != port) {
            sPortInter = "" + port;
        }
        Element stringProp2 = createProperty(document, "stringProp", "HTTPSampler.port", sPortInter);
        eltHTTPSamplerProxy.appendChild(stringProp2);

        // 스키마가 다른 경우 설정
        String schemeInter = "";
        if (!scheme.equalsIgnoreCase(url.getScheme())) {
            schemeInter= url.getScheme();
        }
        Element stringProp3 = createProperty(document, "stringProp", "HTTPSampler.protocol", schemeInter);
        eltHTTPSamplerProxy.appendChild(stringProp3);

        // HTTP 메서드 가져오기
        String methodInter = harRequest.getMethod().name();

        String contentEncodingInter = "";
        // POST, PUT, PATCH 요청의 Content-Type에 따라 contentEncoding 설정
        if ("POST".equalsIgnoreCase(methodInter) || "PUT".equalsIgnoreCase(methodInter) || "PATCH".equalsIgnoreCase(methodInter)) {
            if (harRequest.getHeaders() != null && harRequest.getHeaders().size() > 0) {
                for (HarHeader header : harRequest.getHeaders()) {
                    String headerName = header.getName();
                    String headerValue = header.getValue();

                    if ("Content-Type".equalsIgnoreCase(headerName)) {
                        if ("application/json".equalsIgnoreCase(headerValue)) {
                            contentEncodingInter = "UTF-8";
                            break;
                        }
                    }
                }
            }
        }

        Element stringProp4 = createProperty(document, "stringProp", "HTTPSampler.contentEncoding", contentEncodingInter);
        eltHTTPSamplerProxy.appendChild(stringProp4);

        // 경로 설정
        String pathInter = url.getPath();
        if ("POST".equalsIgnoreCase(methodInter) || "PUT".equalsIgnoreCase(methodInter) || "PATCH".equalsIgnoreCase(methodInter)) {
            // POST, PUT, PATCH 요청의 경우 쿼리 문자열을 경로에 추가
            if (url.getQuery() != null) {
                pathInter += "?" + url.getQuery();
            }
        }

        Element stringProp5 = createProperty(document, "stringProp", "HTTPSampler.path", pathInter);
        eltHTTPSamplerProxy.appendChild(stringProp5);

        Element stringProp6 = createProperty(document, "stringProp", "HTTPSampler.method", methodInter);
        eltHTTPSamplerProxy.appendChild(stringProp6);

        Element boolProp7 = createProperty(document, "boolProp", "HTTPSampler.follow_redirects", "false");
        eltHTTPSamplerProxy.appendChild(boolProp7);

        Element boolProp8 = createProperty(document, "boolProp", "HTTPSampler.auto_redirects", "false");
        eltHTTPSamplerProxy.appendChild(boolProp8);

        Element boolProp9 = createProperty(document, "boolProp", "HTTPSampler.use_keepalive", "true");
        eltHTTPSamplerProxy.appendChild(boolProp9);

        Element boolProp10 = createProperty(document, "boolProp", "HTTPSampler.DO_MULTIPART_POST", doMultiPart);
        eltHTTPSamplerProxy.appendChild(boolProp10);

        Element stringProp11 = createProperty(document, "stringProp", "HTTPSampler.embedded_url_re", null);
        eltHTTPSamplerProxy.appendChild(stringProp11);

        Element stringProp12 = createProperty(document, "stringProp", "HTTPSampler.connect_timeout", null);
        eltHTTPSamplerProxy.appendChild(stringProp12);

        Element stringProp13 = createProperty(document, "stringProp", "HTTPSampler.response_timeout", null);
        eltHTTPSamplerProxy.appendChild(stringProp13);


        eltHTTPSamplerProxy = createHttpSamplerParams(document, harRequest, eltHTTPSamplerProxy);

        return eltHTTPSamplerProxy;
    }

    protected Element createHttpSamplerParams(Document document, HarRequest harRequest, Element eltHTTPSamplerProxy) {
        /*
        <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
        <collectionProp name="Arguments.arguments">
                <elementProp name="mode" elementType="HTTPArgument">
                  <boolProp name="HTTPArgument.always_encode">false</boolProp> // 1
                  // HTTPArgument 요소 생성 및 속성 설정
                  <stringProp name="Argument.name">mode</stringProp>           // 2
                  <stringProp name="Argument.value">mod2</stringProp>          // 3
                  <stringProp name="Argument.metadata">=</stringProp>          // 4
                  <boolProp name="HTTPArgument.use_equals">true</boolProp>     // 5
                </elementProp>
                <elementProp name="tfdLogin" elementType="HTTPArgument">
                  <boolProp name="HTTPArgument.always_encode">true</boolProp>
                  <stringProp name="Argument.name">tfdLogin</stringProp>
                  <stringProp name="Argument.value">${V_LOGIN}</stringProp>
                  <stringProp name="Argument.metadata">=</stringProp>
                  <boolProp name="HTTPArgument.use_equals">true</boolProp>
                </elementProp>
         </collectionProp>
         </elementProp>
         // HTTP 샘플러 프록시의 인자(Arguments) 요소 생성
         */
        Element eltHTTPSamplerProxyElementPropArguments = createElementProp(document, "HTTPsampler.Arguments", "Arguments", "HTTPArgumentsPanel", "Arguments", null);
        Element collectionProp = document.createElement("collectionProp");
        Attr attrcollectionPropname = document.createAttribute("name");
        // collectionProp의 이름 속성 설정
        attrcollectionPropname.setValue("Arguments.arguments");
        collectionProp.setAttributeNode(attrcollectionPropname);
        // 인자가 추가되었는지 여부를 추적하는 플래그
        boolean isParamAdd = false;

        // POST, PUT, PATCH 요청 처리
        if (harRequest.getMethod().equals(HttpMethod.POST) || harRequest.getMethod().equals(HttpMethod.PUT) || harRequest.getMethod().equals(HttpMethod.PATCH)) {
            HarPostData postData = harRequest.getPostData();
            String mimeType = postData.getMimeType();
            mimeType = Utils.extractMimeType(mimeType); // remove charset if exists

            // application/x-www-form-urlencoded 타입 처리
            if ("application/x-www-form-urlencoded".equalsIgnoreCase(mimeType)) {
                Element boolPropPostBodyRaw = createProperty(document, "boolProp", "HTTPSampler.postBodyRaw", "false");
                // HTTPsampler.postBodyRaw 속성 설정 (false)
                eltHTTPSamplerProxy.appendChild(boolPropPostBodyRaw);

                for (HarPostDataParam dataParam : postData.getParams()) {
                    String paramName = dataParam.getName();
                    String paramValue = dataParam.getValue();
                    //String contentType = dataParam.getContentType();
                    //String fileName = dataParam.getFileName();
                    //String comment = dataParam.getComment();

                    addElemProp1(document, paramName, paramValue, collectionProp);

                    isParamAdd = true;
                }
            }

            // multipart/form-data 타입 처리
            if (mimeType != null && mimeType.contains("multipart/form-data")) {
                isParamAdd = true;
                Element boolPropPostBodyRaw = createProperty(document, "boolProp", "HTTPSampler.postBodyRaw", "false");
                // HTTPsampler.postBodyRaw 속성 설정 (false)
                eltHTTPSamplerProxy.appendChild(boolPropPostBodyRaw);
                // 멀티파트 데이터에서 매개변수 추출

                // HarRequest에서 멀티파트 데이터를 추출하여 HarPostData 객체로 변환
                HarPostData postDataModified =  HarForJMeter.extractParamsFromMultiPart(harRequest);

                for (HarPostDataParam dataParam : postDataModified.getParams()) {
                    String paramName = dataParam.getName();
                    String paramValue = dataParam.getValue();
                    String contentType = dataParam.getContentType();
                    String fileName = dataParam.getFileName();
                    //String comment = dataParam.getComment();

                    // 파일이 있는 경우 HTTPsampler.Files 요소 추가
                    if (fileName != null) {
                        addFileElemProp(document, eltHTTPSamplerProxy, contentType, fileName, paramName);
                    } else {
                        addElemProp2(document, paramName, paramValue, collectionProp);
                    }
                }
            }

            if (!isParamAdd) {
                // <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
                Element boolPropPostBodyRaw = createProperty(document, "boolProp", "HTTPSampler.postBodyRaw", "true");
                // HTTPsampler.postBodyRaw 속성 설정 (true)
                eltHTTPSamplerProxy.appendChild(boolPropPostBodyRaw);

                addElemProp3(document, postData, collectionProp);

                isParamAdd = true;
            }
        }

        if (!(harRequest.getMethod().equals(HttpMethod.POST) || harRequest.getMethod().equals(HttpMethod.PUT) || harRequest.getMethod().equals(HttpMethod.PATCH))) {
            // NOT POST NOT PUT NOT PATCH, e.g : DELETE HEAD, OPTIONS, PATCH, PROPFIND

            Element boolPropPostBodyRaw = createProperty(document, "boolProp", "HTTPSampler.postBodyRaw", "false");
            // HTTPsampler.postBodyRaw 속성 설정 (false)
            eltHTTPSamplerProxy.appendChild(boolPropPostBodyRaw);

            for (HarQueryParam queryParam : harRequest.getQueryString()) {
                String paramName = queryParam.getName();
                String paramValue = queryParam.getValue();

                boolean isNeedEncode = false;
                // paramValue에 특정 문자가 포함되어 있으면 인코딩이 필요하다고 설정
                if (paramValue.contains(" ") || paramValue.contains("=") || paramValue.contains("/") || paramValue.contains("/") || paramValue.contains("+")) {
                    isNeedEncode = true;
                }

                // elementProp 생성
                addElemProp4(document, paramName, isNeedEncode, paramValue, collectionProp);
            }
        }

        eltHTTPSamplerProxyElementPropArguments.appendChild(collectionProp);
        eltHTTPSamplerProxy.appendChild(eltHTTPSamplerProxyElementPropArguments);

        return eltHTTPSamplerProxy;
    }

    private static void addElemProp4(Document document, String paramName, boolean isNeedEncode, String paramValue, Element collectionProp) {
        Element elementProp = document.createElement("elementProp");
        Attr attrElementPropname = document.createAttribute("name");
        // elementProp의 이름 속성 설정
        attrElementPropname.setValue(paramName);
        elementProp.setAttributeNode(attrElementPropname);

        Attr attrElementPropelementType = document.createAttribute("elementType");
        // elementProp의 elementType 속성 설정
        attrElementPropelementType.setValue("HTTPArgument");
        elementProp.setAttributeNode(attrElementPropelementType);

        Element boolProp1 = createProperty(document, "boolProp", "HTTPArgument.always_encode", "" + isNeedEncode);
        // HTTPArgument.always_encode 속성 설정
        elementProp.appendChild(boolProp1);

        Element stringProp2 = createProperty(document, "stringProp", "Argument.name", paramName);
        // Argument.name 속성 설정
        elementProp.appendChild(stringProp2);

        Element stringProp3 = createProperty(document, "stringProp", "Argument.value", paramValue);
        // Argument.value 속성 설정
        elementProp.appendChild(stringProp3);

        Element stringProp4 = createProperty(document, "stringProp", "Argument.metadata", "=");
        // Argument.metadata 속성 설정
        elementProp.appendChild(stringProp4);

        Element boolProp5 = createProperty(document, "boolProp", "HTTPArgument.use_equals", "true");
        // HTTPArgument.use_equals 속성 설정
        elementProp.appendChild(boolProp5);

        collectionProp.appendChild(elementProp);
    }

    private static void addElemProp3(Document document, HarPostData postData, Element collectionProp) {
        /*
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">{"name":"Vincent", "code":"GREEN"}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
         */
        // elementProp 생성
        Element elementProp = document.createElement("elementProp");
        Attr attrElementPropname = document.createAttribute("name");
        // elementProp의 이름 속성 설정 (빈 문자열)
        attrElementPropname.setValue("");
        elementProp.setAttributeNode(attrElementPropname);

        Attr attrElementPropelementType = document.createAttribute("elementType");
        // elementProp의 elementType 속성 설정
        attrElementPropelementType.setValue("HTTPArgument");
        elementProp.setAttributeNode(attrElementPropelementType);

        Element boolProp1 = createProperty(document, "boolProp", "HTTPArgument.always_encode", "false");
        // HTTPArgument.always_encode 속성 설정
        elementProp.appendChild(boolProp1);

        String paramValue = postData.getText();
        // postData의 텍스트 값 가져오기
        if (paramValue == null) {
            paramValue = "";
        }

        Element stringProp3 = createProperty(document, "stringProp", "Argument.value", paramValue);
        // Argument.value 속성 설정
        elementProp.appendChild(stringProp3);

        Element stringProp4 = createProperty(document, "stringProp", "Argument.metadata", "=");
        // Argument.metadata 속성 설정
        elementProp.appendChild(stringProp4);

        collectionProp.appendChild(elementProp);
    }

    private void addFileElemProp(Document document, Element eltHTTPSamplerProxy, String contentType, String fileName, String paramName) {
        Element eltHTTPSamplerProxyHTTPsamplerFiles = createElementProp(document, "HTTPsampler.Files", "HTTPFileArgs", null, null, null);
        // HTTPsampler.Files 요소 생성
        Element eltPropCollectionProp = document.createElement("collectionProp");
        Attr attrPropCollectionPropname = document.createAttribute("name");
        // collectionProp의 이름 속성 설정
        attrPropCollectionPropname.setValue("HTTPFileArgs.files");
        eltPropCollectionProp.setAttributeNode(attrPropCollectionPropname);

        if (contentType == null) {
            contentType ="";
        }

        Element eltPropFileName = createElementProp(document, fileName, "HTTPFileArg", null, null, null);
        // HTTPFileArg 요소 생성
        Element stringProp1 = createProperty(document, "stringProp", "File.mimetype", contentType);
        // File.mimetype 속성 설정
        eltPropFileName.appendChild(stringProp1);
        Element stringProp2 = createProperty(document, "stringProp", "File.path", fileName);
        // File.path 속성 설정
        eltPropFileName.appendChild(stringProp2);
        Element stringProp3 = createProperty(document, "stringProp", "File.paramname", paramName);
        // File.paramname 속성 설정
        eltPropFileName.appendChild(stringProp3);

        eltPropCollectionProp.appendChild(eltPropFileName);
        eltHTTPSamplerProxyHTTPsamplerFiles.appendChild(eltPropCollectionProp);

        eltHTTPSamplerProxy.appendChild(eltHTTPSamplerProxyHTTPsamplerFiles);
    }

    private static void addElemProp2(Document document, String paramName, String paramValue, Element collectionProp) {
        // 파일이 없는 경우 일반 HTTPArgument 요소 추가
        Element elementProp = document.createElement("elementProp");
        Attr attrElementPropname = document.createAttribute("name");
        // elementProp의 이름 속성 설정
        attrElementPropname.setValue(paramName);
        elementProp.setAttributeNode(attrElementPropname);

        Attr attrElementPropelementType = document.createAttribute("elementType");
        // elementProp의 elementType 속성 설정
        attrElementPropelementType.setValue("HTTPArgument");
        elementProp.setAttributeNode(attrElementPropelementType);

        Element boolProp1 = createProperty(document, "boolProp", "HTTPArgument.always_encode", "false");
        // HTTPArgument.always_encode 속성 설정
        elementProp.appendChild(boolProp1);

        Element stringProp2 = createProperty(document, "stringProp", "Argument.name", paramName);
        // Argument.name 속성 설정
        elementProp.appendChild(stringProp2);

        Element stringProp3 = createProperty(document, "stringProp", "Argument.value", paramValue);
        // Argument.value 속성 설정
        elementProp.appendChild(stringProp3);

        Element stringProp4 = createProperty(document, "stringProp", "Argument.metadata", "=");
        // Argument.metadata 속성 설정
        elementProp.appendChild(stringProp4);

        Element boolProp5 = createProperty(document, "boolProp", "HTTPArgument.use_equals", "true");
        // HTTPArgument.use_equals 속성 설정
        elementProp.appendChild(boolProp5);

        collectionProp.appendChild(elementProp);
    }

    private static void addElemProp1(Document document, String paramName, String paramValue, Element collectionProp) {
        // elementProp 생성 및 속성 설정
        Element elementProp = document.createElement("elementProp");
        Attr attrElementPropname = document.createAttribute("name");
        // elementProp의 이름 속성 설정
        attrElementPropname.setValue(paramName);
        elementProp.setAttributeNode(attrElementPropname);

        Attr attrElementPropelementType = document.createAttribute("elementType");
        // elementProp의 elementType 속성 설정
        attrElementPropelementType.setValue("HTTPArgument");
        elementProp.setAttributeNode(attrElementPropelementType);

        boolean isNeedEncode = false;
        // paramValue에 특정 문자가 포함되어 있으면 인코딩이 필요하다고 설정
        if (paramValue.contains(" ") || paramValue.contains("=") || paramValue.contains("/") || paramValue.contains("+")) {
            isNeedEncode = true;
        }

        Element boolProp1 = createProperty(document, "boolProp", "HTTPArgument.always_encode", "" + isNeedEncode);
        // HTTPArgument.always_encode 속성 설정
        elementProp.appendChild(boolProp1);

        Element stringProp2 = createProperty(document, "stringProp", "Argument.name", paramName);
        // Argument.name 속성 설정
        elementProp.appendChild(stringProp2);

        Element stringProp3 = createProperty(document, "stringProp", "Argument.value", paramValue);
        // Argument.value 속성 설정
        elementProp.appendChild(stringProp3);

        Element stringProp4 = createProperty(document, "stringProp", "Argument.metadata", "=");
        // Argument.metadata 속성 설정
        elementProp.appendChild(stringProp4);

        Element boolProp5 = createProperty(document, "boolProp", "HTTPArgument.use_equals", "true");
        // HTTPArgument.use_equals 속성 설정
        elementProp.appendChild(boolProp5);

        collectionProp.appendChild(elementProp);
    }


    protected Element createHeaderManager(Document document, HarRequest harRequest, boolean isRemoveCookie, boolean isRemoveCacheRequest) {
        /*
        <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
             <collectionProp name="HeaderManager.headers">
                <elementProp name="Referer" elementType="Header">
                  <stringProp name="Header.name">Referer</stringProp>
                  <stringProp name="Header.value">http://myhost:8180/gestdocqualif/servletMenu</stringProp>
                </elementProp>
                <elementProp name="Accept-Language" elementType="Header">
                  <stringProp name="Header.name">Accept-Language</stringProp>
                  <stringProp name="Header.value">fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3</stringProp>
                </elementProp>
                <elementProp name="Accept-Encoding" elementType="Header">
                  <stringProp name="Header.name">Accept-Encoding</stringProp>
                  <stringProp name="Header.value">gzip, deflate</stringProp>
                </elementProp>
                <elementProp name="User-Agent" elementType="Header">
                  <stringProp name="Header.name">User-Agent</stringProp>
                  <stringProp name="Header.value">Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/113.0</stringProp>
                </elementProp>
              </collectionProp>
            </HeaderManager>
         */
        // HeaderManager 요소 생성
        Element eltHeadManager = document.createElement("HeaderManager");
        Attr attreltHeadManagerguiclass = document.createAttribute("guiclass");
        // HeaderManager의 guiclass 속성 설정
        attreltHeadManagerguiclass.setValue("HeaderPanel");
        eltHeadManager.setAttributeNode(attreltHeadManagerguiclass);

        Attr attreltHeadManagertestclass = document.createAttribute("testclass");
        // HeaderManager의 testclass 속성 설정
        attreltHeadManagertestclass.setValue("HeaderManager");
        eltHeadManager.setAttributeNode(attreltHeadManagertestclass);

        Attr attreltHeadManagertestname = document.createAttribute("testname");
        // HeaderManager의 testname 속성 설정
        attreltHeadManagertestname.setValue("HTTP Header Manager");
        eltHeadManager.setAttributeNode(attreltHeadManagertestname);

        Attr attreltHeadManagerenabled = document.createAttribute("enabled");
        // HeaderManager의 enabled 속성 설정
        attreltHeadManagerenabled.setValue("true");
        eltHeadManager.setAttributeNode(attreltHeadManagerenabled);

        Element headers = createHttpSamplerHeaders(document, harRequest, isRemoveCookie, isRemoveCacheRequest);
        // HTTP 샘플러 헤더 생성 및 추가
        eltHeadManager.appendChild(headers);

        return eltHeadManager;
    }

    protected Element createHttpSamplerHeaders(Document document, HarRequest harRequest, boolean isRemoveCookie, boolean isRemoveCacheRequest) {
        /*
            <collectionProp name="HeaderManager.headers">
                <elementProp name="Referer" elementType="Header">
                  <stringProp name="Header.name">Referer</stringProp>
                  <stringProp name="Header.value">http://myshost:8180/gestdocqualif/servletMenu</stringProp>
                </elementProp>
                <elementProp name="Accept-Language" elementType="Header">
                  <stringProp name="Header.name">Accept-Language</stringProp>
                  <stringProp name="Header.value">fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3</stringProp>
                </elementProp>
                <elementProp name="Accept-Encoding" elementType="Header">
                  <stringProp name="Header.name">Accept-Encoding</stringProp>
                  <stringProp name="Header.value">gzip, deflate</stringProp>
                </elementProp>
              </collectionProp>
         */
        // collectionProp 요소 생성
        Element collectionProp = document.createElement("collectionProp");
        Attr attrcollectionPropname = document.createAttribute("name");
        // collectionProp의 이름 속성 설정
        attrcollectionPropname.setValue("HeaderManager.headers");
        collectionProp.setAttributeNode(attrcollectionPropname);

        if (harRequest.getHeaders() != null && harRequest.getHeaders().size() > 0) {
            for (HarHeader header : harRequest.getHeaders()) {
                String headerName = header.getName();
                String headerValue = header.getValue();

                boolean addThisHearder = true;

                if ("Cookie".equalsIgnoreCase(headerName)) {
                    if (isRemoveCookie) {
                        // 쿠키 관리자를 추가했으므로 쿠키 헤더를 제거합니다.
                        addThisHearder = false;
                    }
                }

                if ("If-Modified-Since".equalsIgnoreCase(headerName) || "If-None-Match".equalsIgnoreCase(headerName) || "If-Last-Modified".equalsIgnoreCase(headerName)) {
                    if (isRemoveCacheRequest) {
                        // no cache If-Modified-Since or If-None-Match because add a Cache Manager
                        // 캐시 관리자를 추가했으므로 캐시 관련 헤더를 제거합니다.
                        addThisHearder = false;
                    }
                }

                if ("Content-Length".equalsIgnoreCase(headerName)) {
                    // Content-Length는 JMeter가 요청 생성 시 계산하므로 제거합니다.
                    addThisHearder = false;
                }

                if (headerName != null && !headerName.isEmpty() && headerName.startsWith(":")) {
                    // HTTP/2 프로토콜에서 ':'로 시작하는 헤더(예: ":authority", ":method", ":path", ":scheme")는 추가하지 않습니다.
                    addThisHearder = false;
                }

                if(addThisHearder) {
                    // 헤더를 추가할 수 있는 경우 elementProp 생성
                    Element elementProp = createElementProp(document, headerName, "Header", null, null, null);

                    Element stringProp1 = createProperty(document, "stringProp", "Header.name", headerName);
                    // Header.name 속성 설정
                    elementProp.appendChild(stringProp1);

                    Element stringProp2 = createProperty(document, "stringProp", "Header.value", headerValue);
                    // Header.value 속성 설정
                    elementProp.appendChild(stringProp2);
                    collectionProp.appendChild(elementProp);
                }
            }
        }

        return collectionProp;
    }

    protected Element createTestActionPause(Document document, String testname, long pauseMs) {
        /*
        <TestAction guiclass="TestActionGui" testclass="TestAction" testname="fca PAUSE TEMPS_COURT" enabled="true">
          <intProp name="ActionProcessor.action">1</intProp>
          <intProp name="ActionProcessor.target">0</intProp>
          <stringProp name="ActionProcessor.duration">${K_TEMPS_COURT}</stringProp>
        </TestAction>
         */
        // TestAction 요소 생성
        Element eltTestAction = document.createElement("TestAction");
        Attr attrTestActionguiclass = document.createAttribute("guiclass");
        // TestAction의 guiclass 속성 설정
        attrTestActionguiclass.setValue("TestActionGui");
        eltTestAction.setAttributeNode(attrTestActionguiclass);

        Attr attrTestActiontestclass = document.createAttribute("testclass");
        // TestAction의 testclass 속성 설정
        attrTestActiontestclass.setValue("TestAction");
        eltTestAction.setAttributeNode(attrTestActiontestclass);

        Attr attrTestActiontestname = document.createAttribute("testname");
        // TestAction의 testname 속성 설정
        attrTestActiontestname.setValue(testname);
        eltTestAction.setAttributeNode(attrTestActiontestname);

        Attr attrTestActionenabled = document.createAttribute("enabled");
        // TestAction의 enabled 속성 설정
        attrTestActionenabled.setValue("true");
        eltTestAction.setAttributeNode(attrTestActionenabled);

        Element eltIntProp1 = createProperty(document, "intProp","ActionProcessor.action", "1");
        // ActionProcessor.action 속성 설정
        eltTestAction.appendChild(eltIntProp1);
        Element eltIntProp2 = createProperty(document, "intProp","ActionProcessor.target", "0");
        // ActionProcessor.target 속성 설정
        eltTestAction.appendChild(eltIntProp2);
        Element eltStringProp3 = createProperty(document, "stringProp","ActionProcessor.duration", "" + pauseMs);
        eltTestAction.appendChild(eltStringProp3);

        return eltTestAction;
    }

    HashMap getSchemeHostPortFirstPageOrUrl(Har har) throws URISyntaxException {
        String scheme = "";
        // 스키마 변수 초기화
        String host = "";
        // 호스트 변수 초기화
        int iPort = 0;
        // 포트 변수 초기화

        List<HarEntry> lEntries = har.getLog().getEntries();
        if (lEntries != null && lEntries.size() > 0) {
            HarEntry harEntryInter = lEntries.get(0);
            URI pageUrl = new URI(harEntryInter.getRequest().getUrl());
            scheme = pageUrl.getScheme(); // http or https
            // URL에서 스키마 추출 (http 또는 https)
            host = pageUrl.getHost(); // google.com
            // URL에서 호스트 추출 (예: google.com)
            iPort = pageUrl.getPort(); // -1 (default for 80 or 443) or port number likes 8080
            // URL에서 포트 추출 (-1은 기본 포트 80 또는 443을 의미)
            if (iPort == -1 && "http".equalsIgnoreCase(scheme)) {
                iPort = 80;
            }
            if (iPort == -1 && "https".equalsIgnoreCase(scheme)) {
                iPort = 443;
            }
        }

        HashMap hashMap = new HashMap<>();
        hashMap.put(K_SCHEME, scheme);
        hashMap.put(K_HOST, host);
        hashMap.put(K_PORT, "" + iPort); // port is a String in the hashMap
        // 스키마, 호스트, 포트 정보를 HashMap에 저장

        return hashMap;
    }

    /**
     * Save the JMX Document in a XML file
     * @param document JMX Document
     * @param jmxXmlFileOut XML file to write
     * @throws TransformerException error when write XML file
     */
    public static void saveXmFile(Document document, String jmxXmlFileOut) throws TransformerException {
        // create the xml file
        // XML 파일 생성
        //transform the DOM Object to an XML File
        // DOM 객체를 XML 파일로 변환
        LOGGER.fine("saveXmFile, param jmxXmlFileOut=<" + jmxXmlFileOut + ">" );
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        DOMSource domSource = new DOMSource(document);
        // DOMSource 생성
        try {
            Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jmxXmlFileOut), StandardCharsets.UTF_8));
            StreamResult streamResult = new StreamResult(out);
            // StreamResult 생성
            transformer.transform(domSource, streamResult);
            // 변환 수행
        } catch (Exception e) {
            throw new TransformerException(e);
        }
    }
}
