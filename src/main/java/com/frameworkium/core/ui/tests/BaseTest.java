package com.frameworkium.core.ui.tests;

import com.frameworkium.core.common.listeners.*;
import com.frameworkium.core.common.reporting.allure.AllureLogger;
import com.frameworkium.core.ui.capture.ScreenshotCapture;
import com.frameworkium.core.ui.driver.*;
import com.frameworkium.core.ui.listeners.*;
import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.testng.SauceOnDemandAuthenticationProvider;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.remote.SessionId;
import org.testng.annotations.*;
import ru.yandex.qatools.allure.annotations.Issue;
import ru.yandex.qatools.allure.annotations.TestCaseId;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

@Listeners({CaptureListener.class, ScreenshotListener.class,
        MethodInterceptor.class, SauceLabsListener.class,
        TestListener.class, ResultLoggerListener.class})
public abstract class BaseTest
        implements SauceOnDemandSessionIdProvider, SauceOnDemandAuthenticationProvider {

    public static final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static String userAgent;

    private static ThreadLocal<Boolean> requiresReset;
    private static ThreadLocal<ScreenshotCapture> capture;
    private static ThreadLocal<DriverType> driverType;
    private static List<DriverType> activeDriverTypes =
            Collections.synchronizedList(new ArrayList<>());
    private static Logger logger = LogManager.getLogger(BaseTest.class);

    /**
     * Method which runs first upon running a test, it will do the following:
     * - Retrieve the desired driver type and initialise the driver
     * - Initialise whether the browser needs resetting
     * - Initialise the screenshot capture
     */
    @BeforeSuite(alwaysRun = true)
    public static void instantiateDriverObject() {
        driverType = ThreadLocal.withInitial(() -> {
            DriverType driverType =
                    new DriverSetup().returnDesiredDriverType();
            driverType.instantiate();
            activeDriverTypes.add(driverType);
            return driverType;
        });
        requiresReset = ThreadLocal.withInitial(() -> Boolean.FALSE);
        capture = ThreadLocal.withInitial(() -> null);
    }

    /**
     * The methods which configure the browser once a test runs
     * - Maximises browser based on the driver type
     * - Initialises screenshot capture if needed
     * - Resets the browser if another test ran prior
     * - Sets the user agent of the browser
     *
     * @param testMethod - The test method name of the test
     */
    @BeforeMethod(alwaysRun = true)
    public static void configureBrowserBeforeTest(Method testMethod) {
        try {
            configureDriverBasedOnParams();
            initialiseNewScreenshotCapture(testMethod);
        } catch (Exception e) {
            logger.error(e);
            throw new RuntimeException("Failed to configure browser.", e);
        }
    }

    /**
     * Ran as part of the initialiseDriverObject, configures parts of the driver
     */
    private static void configureDriverBasedOnParams() {
        requiresReset.set(driverType.get().resetBrowser(requiresReset.get()));
        driverType.get().maximiseBrowserWindow();
        userAgent = determineUserAgent();
    }

    /**
     * Initialise the screenshot capture and link to issue/test case id
     *
     * @param testMethod - Test method passed from the test script
     */
    private static void initialiseNewScreenshotCapture(Method testMethod) {
        if (ScreenshotCapture.isRequired()) {
            String testID = getIssueOrTestCaseIdAnnotation(testMethod);
            if (testID.isEmpty()) {
                logger.warn("Method {} doesn't have a TestID annotation.", testMethod.getName());
                testID = StringUtils.abbreviate(testMethod.getName(), 20);
            }
            capture.set(new ScreenshotCapture(testID, getDriver()));
        }
    }

    /**
     * Attempts to retrieve the user agent from the browser
     *
     * @return - The user agent or error message
     */
    private static String determineUserAgent() {
        String ua;
        try {
            ua = (String) getDriver().executeScript("return navigator.userAgent;");
        } catch (Exception e) {
            ua = "Unable to fetch UserAgent";
        }
        logger.debug("User agent is: '" + ua + "'");
        return ua;
    }

    /**
     * Throws {@link IllegalStateException} if {@link TestCaseId} and {@link Issue}
     * are specified inconstantly.
     *
     * @param method the method to check for test ID annotations.
     * @return either the {@link TestCaseId} and {@link Issue} value if specified,
     * otherwise will return an empty string
     */
    public static String getIssueOrTestCaseIdAnnotation(Method method) {
        TestCaseId tcIdAnnotation = method.getAnnotation(TestCaseId.class);
        Issue issueAnnotation = method.getAnnotation(Issue.class);

        if (null != issueAnnotation && null != tcIdAnnotation) {
            if (!issueAnnotation.value().equals(tcIdAnnotation.value())) {
                throw new IllegalStateException(
                        "TestCaseId and Issue annotation are both specified but " +
                                "not equal for method: " + method.toString());
            } else {
                return issueAnnotation.value();
            }
        } else if (null != issueAnnotation) {
            return issueAnnotation.value();
        } else if (null != tcIdAnnotation) {
            return tcIdAnnotation.value();
        } else {
            return StringUtils.EMPTY;
        }
    }

    /**
     * Returns the {@link WebDriverWrapper} instance for the requesting thread
     *
     * @return - WebDriver object
     */
    public static WebDriverWrapper getDriver() {
        return driverType.get().getDriver();
    }

    /** Loops through all active driver types and tears them down */
    @AfterSuite(alwaysRun = true)
    public static void closeDriverObject() {
        try {
            activeDriverTypes.stream().parallel()
                    .forEach(DriverType::tearDownDriver);
        } catch (Exception e) {
            logger.warn("Session quit unexpectedly.", e);
        }
    }

    /** Shuts down the {@link ExecutorService} */
    @AfterSuite(alwaysRun = true)
    public static void shutdownExecutor() {
        try {
            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("Executor was interrupted while shutting down. " +
                    "Some tasks might not have been executed.");
        }
    }

    /** Creates the allure properties for the report, after the test run */
    @AfterSuite(alwaysRun = true)
    public static void createAllureProperties() {
        com.frameworkium.core.common.reporting.allure.AllureProperties.create();
    }

    /** @return - Screenshot capture object for the current test */
    public static ScreenshotCapture getCapture() {
        return capture.get();
    }

    /** @return the Job id for the current thread */
    @Override
    public String getSessionId() {
        WebDriverWrapper driver = getDriver();
        SessionId sessionId = driver.getWrappedRemoteWebDriver().getSessionId();
        return (sessionId == null) ? null : sessionId.toString();
    }

    /**
     * @return the {@link SauceOnDemandAuthentication} instance containing the Sauce username/access key
     */
    @Override
    public SauceOnDemandAuthentication getAuthentication() {
        return new SauceOnDemandAuthentication();
    }

    /**
     * Logs the start of a step to your allure report
     * Other steps will be sub-steps until you call stepFinish
     *
     * @param stepName the name of the step
     */
    public void __stepStart(String stepName) {
        AllureLogger.__stepStart(stepName);
    }

    /** Logs the end of a step */
    public void __stepFinish() {
        AllureLogger.__stepFinish();
    }

}
