package org.example.wb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.function.Consumer;

public class WbStreamParser {
	private final JsonFactory factory = new JsonFactory();

	public static final class ProductFlat {
		public String id;
		public String name;
		public String feedbackPoints;
		public String totalQuantity;
		public String supplier;
		public int priceRub;
	}

	public void parseProducts(String json, Consumer<ProductFlat> consumer) {
		try (JsonParser p = factory.createParser(json)) {
			// navigate to data.products[]
			if (p.nextToken() != JsonToken.START_OBJECT) return;
			while (p.nextToken() != JsonToken.END_OBJECT) {
				String field = p.getCurrentName();
				if (field == null) { p.skipChildren(); continue; }
				p.nextToken();
				if ("data".equals(field) && p.currentToken() == JsonToken.START_OBJECT) {
					parseData(p, consumer);
				} else {
					p.skipChildren();
				}
			}
		} catch (IOException ignored) {
		}
	}

	private void parseData(JsonParser p, Consumer<ProductFlat> consumer) throws IOException {
		while (p.nextToken() != JsonToken.END_OBJECT) {
			String field = p.getCurrentName();
			p.nextToken();
			if ("products".equals(field) && p.currentToken() == JsonToken.START_ARRAY) {
				while (p.nextToken() != JsonToken.END_ARRAY) {
					ProductFlat flat = parseProduct(p);
					if (flat != null) consumer.accept(flat);
				}
			} else {
				p.skipChildren();
			}
		}
	}

	private ProductFlat parseProduct(JsonParser p) throws IOException {
		ProductFlat flat = new ProductFlat();
		if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null; }
		while (p.nextToken() != JsonToken.END_OBJECT) {
			String field = p.getCurrentName();
			p.nextToken();
			if ("id".equals(field)) flat.id = p.getValueAsString();
			else if ("name".equals(field)) flat.name = p.getValueAsString();
			else if ("feedbackPoints".equals(field)) flat.feedbackPoints = p.getValueAsString();
			else if ("totalQuantity".equals(field)) flat.totalQuantity = p.getValueAsString();
			else if ("supplier".equals(field)) flat.supplier = p.getValueAsString();
			else if ("sizes".equals(field) && p.currentToken() == JsonToken.START_ARRAY) {
				if (flat.priceRub == 0) flat.priceRub = parsePriceRub(p);
			} else {
				p.skipChildren();
			}
		}
		return flat;
	}

	private int parsePriceRub(JsonParser p) throws IOException {
		int price = 0;
		while (p.nextToken() != JsonToken.END_ARRAY) {
			if (p.currentToken() == JsonToken.START_OBJECT) {
				while (p.nextToken() != JsonToken.END_OBJECT) {
					String f = p.getCurrentName();
					p.nextToken();
					if ("price".equals(f) && p.currentToken() == JsonToken.START_OBJECT) {
						while (p.nextToken() != JsonToken.END_OBJECT) {
							String pf = p.getCurrentName();
							p.nextToken();
							if ("product".equals(pf)) {
								int v = p.getValueAsInt();
								if (v != 0) { price = v / 100; break; }
							}
						}
					}
				}
			}
		}
		return price;
	}
}


