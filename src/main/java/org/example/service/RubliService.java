package org.example.service;

import org.example.messaging.MessageFormatter;
import org.example.messaging.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RubliService {

    private static final Logger log = LoggerFactory.getLogger(RubliService.class);
    
    private final MessageFormatter formatter;
    private final SentCache sentCache;
    private final Set<String> foodCategories;
    private final Set<String> childrenCategories;
    private final double resendThreshold = 0.1; // 10% порог изменения процента
    private final double priceResendThreshold = 0.15; // Не используется, но оставляем для совместимости

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

        // COMMUNITY: percent >= 1 ИЛИ (cashback - price >= 199 И percent > 1)
        boolean meetsCommunityCondition = percent >= 1 || (cashback - price >= 199 && percent > 1);
        if (meetsCommunityCondition && shouldRoute(article, ChannelType.COMMUNITY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.COMMUNITY, "-1002397733938", 8, null, payload, article, percent));
        }

        // HUNDRED: percent >= 1
        if (percent >= 1 && shouldRoute(article, ChannelType.HUNDRED, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.HUNDRED, "-1003301933904", 2, null, payload, article, percent));
        }
        // NINETY: percent >= 0.9 (если не попал в HUNDRED)
        else if (percent >= 0.9 && shouldRoute(article, ChannelType.NINETY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.NINETY, "-1003301933904", 4, null, payload, article, percent));
        }
        // EIGHTY: percent >= 0.8 (если не попал в HUNDRED или NINETY)
        else if (percent >= 0.8 && shouldRoute(article, ChannelType.EIGHTY, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.EIGHTY, "-1003301933904", 6, null, payload, article, percent));
        }

        // BIG: проверяем отдельно (может совпадать с другими каналами)
        if (shouldRoute(article, ChannelType.BIG, percent, price)) {
            if ((percent > 0.49 && cashback >= 1000 && cashback < 2500)
                    || (percent > 0.59 && cashback >= 699 && cashback < 1000 && percent < 0.9)
                    || (percent >= 0.4 && cashback >= 2500)) {
                messages.add(new OutgoingMessage(ChannelType.BIG, "-1003301933904", 10, null, payload, article, percent));
            }
        }

        // FOOD: только для категорий еды, percent >= 0.45
        if (foodCategories.contains(context.categoryUrl())
                && percent >= 0.45
                && shouldRoute(article, ChannelType.FOOD, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.FOOD, "-1003301933904", 12, null, payload, article, percent));
        }

        // CHILDREN: только для категорий детей, percent >= 0.5
        if (childrenCategories.contains(context.categoryUrl())
                && percent >= 0.5
                && shouldRoute(article, ChannelType.CHILDREN, percent, price)) {
            messages.add(new OutgoingMessage(ChannelType.CHILDREN, "-1003301933904", 8, null, payload, article, percent));
        }

        // FREE канал - проверяем shouldRoute, чтобы не засорять очередь дубликатами
        // Если товар уже был отправлен в FREE с теми же параметрами - не добавляем
        // Если параметры изменились (процент на 10%+) или товар новый - добавляем
        // Задержка 2 мин 20 сек обрабатывается через mapOnSent и планировщик в TelegramDispatcher
        // ВАЖНО: для FREE канала НЕ сохраняем в файл при первой проверке, только после фактической отправки
        if (percent >= 0.68) {
            if (shouldRouteFree(article, percent, price)) {
                messages.add(new OutgoingMessage(ChannelType.FREE, "-1003301933904", 632, null, payload, article, percent, price));
            }
        }
//        // Проверяем каждый канал отдельно с простой логикой: absent || changed
//        // HUNDRED: percent >= 1
//        if (percent >= 1 && shouldRoute(article, ChannelType.HUNDRED, percent, price)) {
//            messages.add(new OutgoingMessage(ChannelType.HUNDRED, "-1002340997107", 2, "-1002402655346", payload, article, percent));
//        }
//        // NINETY: percent >= 0.9 (если не попал в HUNDRED)
//        else if (percent >= 0.9 && shouldRoute(article, ChannelType.NINETY, percent, price)) {
//            messages.add(new OutgoingMessage(ChannelType.NINETY, "-1002340997107", 4, "-1002446322077", payload, article, percent));
//        }
//        // EIGHTY: percent >= 0.8 (если не попал в HUNDRED или NINETY)
//        else if (percent >= 0.8 && shouldRoute(article, ChannelType.EIGHTY, percent, price)) {
//            messages.add(new OutgoingMessage(ChannelType.EIGHTY, "-1002340997107", 6, "-1002305962649", payload, article, percent));
//        }
//
//        // BIG: проверяем отдельно (может совпадать с другими каналами)
//        if (shouldRoute(article, ChannelType.BIG, percent, price)) {
//            if ((percent > 0.49 && cashback >= 1000 && cashback < 2500)
//                    || (percent > 0.59 && cashback >= 699 && cashback < 1000 && percent < 0.9)
//                    || (percent >= 0.4 && cashback >= 2500)) {
//                messages.add(new OutgoingMessage(ChannelType.BIG, "-1002340997107", 13, "-1002290311759", payload, article, percent));
//            }
//        }
//
//        // FOOD: только для категорий еды, percent >= 0.45
//        if (foodCategories.contains(context.categoryUrl())
//                && percent >= 0.45
//                && shouldRoute(article, ChannelType.FOOD, percent, price)) {
//            messages.add(new OutgoingMessage(ChannelType.FOOD, "-1002340997107", 89330, "-1002474423617", payload, article, percent));
//        }
//
//        // CHILDREN: только для категорий детей, percent >= 0.5
//        if (childrenCategories.contains(context.categoryUrl())
//                && percent >= 0.5
//                && shouldRoute(article, ChannelType.CHILDREN, percent, price)) {
//            messages.add(new OutgoingMessage(ChannelType.CHILDREN, "-1002340997107", 255209, "-1002805053383", payload, article, percent));
//        }
//
//        // FREE канал - проверяем shouldRoute, чтобы не засорять очередь дубликатами
//        // Если товар уже был отправлен в FREE с теми же параметрами - не добавляем
//        // Если параметры изменились (процент на 10%+) или товар новый - добавляем
//        // Задержка 2 мин 20 сек обрабатывается через mapOnSent и планировщик в TelegramDispatcher
//        // ВАЖНО: для FREE канала НЕ сохраняем в файл при первой проверке, только после фактической отправки
//        if (percent >= 0.68) {
//            if (shouldRouteFree(article, percent, price)) {
//                messages.add(new OutgoingMessage(ChannelType.FREE, "-1002346226214", null, null, payload, article, percent, price));
//            }
//        }
        
        return messages;
    }

    private boolean shouldRoute(String article, ChannelType type, double percent, double price) {
        return sentCache.shouldSend(article, type, percent, resendThreshold, price, priceResendThreshold);
    }
    
    private boolean shouldRouteFree(String article, double percent, double price) {
        // Для FREE канала НЕ сохраняем в файл при первой проверке (saveToFile=false)
        // Сохранение произойдет только после фактической отправки в TelegramDispatcher
        return sentCache.shouldSend(article, ChannelType.FREE, percent, resendThreshold, price, priceResendThreshold, false);
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


