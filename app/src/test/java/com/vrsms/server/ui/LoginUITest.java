package com.vrsms.server.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real Chrome browser against a running frontend. Tagged "ui" so
 * it never runs on a CI agent, which has neither a browser nor the app.
 */
@Tag("ui")
public class LoginUITest {

    private WebDriver driver;
    private WebDriverWait wait;

    @BeforeEach
    public void setUp() {
        // Sets up Chrome to run automatically
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--remote-allow-origins=*");
        driver = new ChromeDriver(options);

        // We use explicit waits instead of implicit, making the robot much smarter
        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        driver.manage().window().maximize();
    }

    @Test
    public void testAdminLogin_NavigatesToDashboard() {
        // 1. Tell the robot to open your React App
        driver.get("http://localhost:5173");

        // 2. SWITCH TABS: Find the "Staff" tab and click it
        WebElement staffTab = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[contains(text(), 'Staff') or contains(text(), 'STAFF')]")));
        staffTab.click();

        // Pause to let React switch the forms visually
        try { Thread.sleep(500); } catch (InterruptedException e) {}

        // 3. ENTER PHONE: Find the text input box for phone number
        WebElement phoneInput = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//input[@type='text' or @type='tel']")
        ));

        // Human-like typing: Clear it first, then type piece by piece so React registers it
        phoneInput.clear();
        for (char c : "9999999999".toCharArray()) {
            phoneInput.sendKeys(String.valueOf(c));
            try { Thread.sleep(50); } catch (Exception e) {}
        }

        // 4. ENTER PASSWORD: Find the password box and type
        WebElement passwordInput = driver.findElement(By.cssSelector("input[type='password']"));
        passwordInput.clear();
        passwordInput.sendKeys("admin123");

        // 5. CLICK LOGIN: Look specifically for your "Secure Login" button text
        WebElement loginButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(text(), 'Secure Login') or contains(text(), 'Login')]")
        ));
        loginButton.click();

        // 6. VERIFY: Wait up to 10 seconds for the URL to change to a success page
        wait.until(ExpectedConditions.or(
                ExpectedConditions.urlContains("staff"),
                ExpectedConditions.urlContains("manager"),
                ExpectedConditions.urlContains("dashboard")
        ));

        // Assert that the URL successfully changed (with null safety for IntelliJ)
        String currentUrl = driver.getCurrentUrl();
        assertTrue(currentUrl != null && (currentUrl.contains("staff") || currentUrl.contains("manager") || currentUrl.contains("dashboard")),
                "The robot failed to reach the dashboard after logging in. Current URL: " + currentUrl);
    }

    @AfterEach
    public void tearDown() {
        // Close the browser when the test is done
        if (driver != null) {
            driver.quit();
        }
    }
}