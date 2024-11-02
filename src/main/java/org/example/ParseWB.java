package org.example;

import java.util.*;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.By;
import org.openqa.selenium.firefox.FirefoxDriver;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;

public class ParseWB {
    void Parse(String url){

        WebDriver webDriver = new FirefoxDriver();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("cruises.txt",true))) {
            webDriver.get(url);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }finally {
            webDriver.quit();
        }
    }
}
