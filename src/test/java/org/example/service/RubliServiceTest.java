package org.example.service;

import org.example.messaging.MessageFormatter;
import org.example.messaging.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubliServiceTest {

    private RubliService service;
    private SentCache cache;

    @BeforeEach
    void setUp() {
        cache = new SentCache();
        service = new RubliService(
                new MessageFormatter(),
                cache,
                Set.of("food-cat"),
                Set.of("children-cat")
        );
    }

    @Test
    void routesToMultipleChannelsBasedOnPercentages() {
        RubliService.ProductContext context = new RubliService.ProductContext(
                "12345",
                "Test Product",
                "food-cat",
                1000,
                2500,
                "50",
                2.5
        );

        List<OutgoingMessage> messages = service.evaluate(context);
        Set<ChannelType> channelTypes = messages.stream()
                .map(OutgoingMessage::getChannelType)
                .collect(Collectors.toSet());

        assertTrue(channelTypes.contains(ChannelType.BIG), "Should route to BIG");
        assertTrue(channelTypes.contains(ChannelType.HUNDRED), "Should route to 100%");
        assertTrue(channelTypes.contains(ChannelType.FOOD), "Should route to FOOD");
        assertTrue(channelTypes.contains(ChannelType.COMMUNITY), "Should route to COMMUNITY");
    }

    @Test
    void duplicatePercentDoesNotCreateDuplicates() {
        RubliService.ProductContext context = new RubliService.ProductContext(
                "999",
                "Another",
                "generic",
                500,
                500,
                "10",
                1.0
        );

        List<OutgoingMessage> first = service.evaluate(context);
        boolean allowedAgain = cache.shouldSend("999", ChannelType.HUNDRED, 1.0, 0.15, 500, 0.15);
        assertTrue(first.stream().anyMatch(msg -> msg.getChannelType() == ChannelType.HUNDRED));
        assertFalse(allowedAgain, "Повторная отправка в тот же канал должна блокироваться");
    }

    @Test
    void childrenCategoryRoutesOnlyOnce() {
        RubliService.ProductContext context = new RubliService.ProductContext(
                "777",
                "Kids",
                "children-cat",
                400,
                260,
                "8",
                0.65
        );

        List<OutgoingMessage> first = service.evaluate(context);
        boolean hasChildren = first.stream().anyMatch(msg -> msg.getChannelType() == ChannelType.CHILDREN);
        assertTrue(hasChildren, "Должен быть маршрут в канал Children");

        boolean secondAllowed = cache.shouldSend("777", ChannelType.CHILDREN, 0.65, 0.15, 400, 0.15);
        assertFalse(secondAllowed, "Повторная попытка для канала Children должна быть заблокирована");
    }

    @Test
    void communityTriggeredAtHalfPercent() {
        RubliService.ProductContext context = new RubliService.ProductContext(
                "555",
                "HalfOff",
                "generic",
                1000,
                500,
                "15",
                0.5
        );

        List<OutgoingMessage> messages = service.evaluate(context);
        boolean hasCommunity = messages.stream()
                .anyMatch(msg -> msg.getChannelType() == ChannelType.COMMUNITY);
        assertTrue(hasCommunity, "Сообщение должно уйти в COMMUNITY при 50% выгоды");
    }

    @Test
    void resendWhenPriceDropsSignificantly() {
        RubliService.ProductContext first = new RubliService.ProductContext(
                "222",
                "Discount Item",
                "generic",
                2000,
                1000,
                "12",
                0.5
        );
        RubliService.ProductContext second = new RubliService.ProductContext(
                "222",
                "Discount Item",
                "generic",
                1600, // 20% drop
                800,
                "12",
                0.5
        );

        List<OutgoingMessage> firstMessages = service.evaluate(first);
        assertFalse(firstMessages.isEmpty());

        List<OutgoingMessage> secondMessages = service.evaluate(second);
        assertFalse(secondMessages.isEmpty(), "При снижении цены >15% товар должен отправиться снова");
    }
}


