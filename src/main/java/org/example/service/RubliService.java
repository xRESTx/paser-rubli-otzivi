package org.example.service;

import org.example.messaging.MessageFormatter;
import org.example.messaging.OutgoingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RubliService {

    private final MessageFormatter formatter;
    private final SentCache sentCache;
    private final Set<String> foodCategories;
    private final Set<String> childrenCategories;
    private final double resendThreshold = 0.15;
    private final double priceResendThreshold = 0.15;

    public RubliService(MessageFormatter formatter,
                        SentCache sentCache,
                        Set<String> foodCategories,
                        Set<String> childrenCategories) {
        this.formatter = formatter;
        this.sentCache = sentCache;
        this.foodCategories = foodCategories;
        this.childrenCategories = childrenCategories;
    }

    public List<OutgoingMessage> evaluate(ProductContext context) {
        double percent = context.percent();
        double cashback = context.cashback();
        double price = context.price();
        String article = context.article();
        String payload = formatter.format(context.article(), context.name(), price, cashback, percent, context.stock());

        List<OutgoingMessage> messages = new ArrayList<>();
        if (shouldRoute(article, ChannelType.BIG, percent, price)) {
            if ((percent > 0.49 && cashback >= 1000 && cashback < 2500)
                    || (percent > 0.59 && cashback >= 699 && cashback < 1000 && percent < 0.9)
                    || (percent >= 0.4 && cashback >= 2500)) {
                messages.add(new OutgoingMessage(ChannelType.BIG, "-1002340997107", 13, "-1002290311759", payload, article, percent));
            }
        }
        if (percent >= 1 && shouldRoute(article, ChannelType.HUNDRED, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.HUNDRED, "-1002340997107", 2, "-1002402655346", payload, article, percent));
        } else if (percent >= 0.9 && shouldRoute(article, ChannelType.NINETY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.NINETY, "-1002340997107", 4, "-1002446322077", payload, article, percent));
        } else if (percent >= 0.8 && shouldRoute(article, ChannelType.EIGHTY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.EIGHTY, "-1002340997107", 6, "-1002305962649", payload, article, percent));
        }

        if ((percent >= 1.5 || (cashback - price >= 199 && percent > 1)) && shouldRoute(article, ChannelType.COMMUNITY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.COMMUNITY, "-1002397733938", 8, null, payload, article, percent));
        }
        if (foodCategories.contains(context.categoryUrl())
                && percent >= 0.45
                && shouldRoute(article, ChannelType.FOOD, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.FOOD, "-1002340997107", 89330, "-1002474423617", payload, article, percent));
        }
        if (childrenCategories.contains(context.categoryUrl())
                && percent >= 0.5
                && shouldRoute(article, ChannelType.CHILDREN, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.CHILDREN, "-1002340997107", 255209, "-1002805053383", payload, article, percent));
        }
        return messages;
    }

    private boolean shouldRoute(String article, ChannelType type, double percent, double price) {
        return sentCache.shouldSend(article, type, percent, resendThreshold, price, priceResendThreshold);
    }

    public record ProductContext(
            String article,
            String name,
            String categoryUrl,
            double price,
            double cashback,
            String stock,
            double percent
    ) {
    }
}


