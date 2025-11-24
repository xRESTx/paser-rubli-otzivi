package org.example;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * Простой раннер для тестов, который можно запустить напрямую через Java,
 * минуя проблемы с Gradle Test Executor.
 */
public class TestRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(
                        DiscoverySelectors.selectPackage("org.example.service"),
                        DiscoverySelectors.selectPackage("org.example.parser"),
                        DiscoverySelectors.selectPackage("org.example.http")
                )
                .build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);

        System.out.println("Запуск тестов...");
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        
        // Выводим детали неудачных тестов
        if (summary.getTestsFailedCount() > 0) {
            System.out.println("\n=== Детали неудачных тестов ===");
            summary.getFailures().forEach(failure -> {
                System.out.println("Тест: " + failure.getTestIdentifier().getDisplayName());
                System.out.println("Ошибка: " + failure.getException().getMessage());
                if (failure.getException().getCause() != null) {
                    System.out.println("Причина: " + failure.getException().getCause().getMessage());
                }
                System.out.println();
            });
            System.exit(1);
        }
        System.exit(0);
    }
}

