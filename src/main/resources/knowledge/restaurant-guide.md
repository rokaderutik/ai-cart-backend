# AI Cart demo restaurant guide

## Restaurant and service scope {#restaurant-overview}

This is a fictional Indian restaurant used to demonstrate AI Cart. The menu and policies are sample content for review, not verified facts about an operating restaurant. The menu includes vegetarian, vegan and non-vegetarian dishes, breads, rice, starters, sides, desserts and drinks. Recommendations should use listed ingredients and descriptions. The cart holds an order draft; it does not confirm payment, delivery, table reservations or acceptance by a kitchen. No opening hours, address or delivery area have been established for this demo. If asked for those details, say they are not provided instead of inventing them.

## Customization requests {#customization}

The demo can explain possible requests, such as less chilli or omitting a garnish, but it cannot guarantee that the kitchen can fulfil them. Some dishes use prepared gravies or marinades, so an ingredient may not be removable. Removing a visible ingredient does not establish that the whole dish is free from that ingredient. The current cart stores only dishes and quantities; it has no customization or special-instruction field. Do not claim a customization has been saved or sent to the kitchen. Ask the restaurant to confirm a request before treating the changed recipe as available.

## Spice preferences {#spice-preferences}

The menu uses an integer scale from 0 to 5: 0 means no chilli heat, 1 mild, 2 medium, 3 spicy, 4 very spicy and 5 extra hot. These values describe the standard recipe. They do not guarantee that a person will experience the dish at a particular intensity. Black pepper can contribute heat even without chilli. A request for non-spicy food should favour level 0; mild requests favour levels 0 or 1. A spicy request generally favours levels 3 to 5, with clarification when the person has a particular tolerance. Lowering spice through customization requires restaurant confirmation.

## Portions and sharing {#serving-sizes}

For this fictional menu, a main curry or rice dish is intended as an individual serving, with appetite and accompaniments affecting how much a person needs. Breads are ordered by quantity; one bread entry represents one bread. A dessert, side or drink entry represents one serving. Starter portions are not specified by weight or piece count. These are demo serving conventions, not measured nutritional or portion guarantees. When helping a group, ask about the number of people, appetite and preferred accompaniments. Do not infer exact weights, calories or how many people a dish will certainly satisfy.

## Dietary terminology {#dietary-terminology}

VEGETARIAN denotes a recipe without meat or fish and can include dairy. In this demo, egg-containing dishes are treated as non-vegetarian. VEGAN denotes a listed recipe without animal-derived ingredients, including milk, yogurt, butter, ghee and eggs. Vegan dishes may also carry the vegetarian tag. An absent dietary tag means no dietary guarantee is supplied by that tag. Ingredient lists describe the demo recipe and cannot establish how an operating kitchen handles food. Do not infer gluten-free, nut-free, Jain, halal or other certifications from a dish name or a missing ingredient. Ask for restaurant confirmation when that requirement matters.

## Allergens and shared preparation {#allergens}

This demo does not provide certified allergen-free dishes or a verified kitchen allergen-management policy. Menu ingredients can identify obvious concerns such as dairy, wheat, peanuts, tree nuts, eggs and fish, but they are not a complete allergen declaration. Sauces, compound ingredients and preparation equipment may introduce additional allergens or cross-contact. A vegetarian or vegan tag does not mean allergy-safe. Never promise that removing an ingredient makes a dish safe for an allergy. For an allergy request, explain these limits and ask the person to confirm ingredients and preparation with the restaurant before ordering.

## Preparation and waiting times {#preparation}

Some menu descriptions refer to grilling, tandoor baking, frying or slow-cooked gravies. These describe preparation styles, not live kitchen progress. Cooking and dispatch times are not configured for the demo. Do not invent an estimated ready time or claim that an order is being prepared. Different dishes may need different preparation times in a real restaurant, and custom requests may need separate confirmation. The current cart is an editable draft, so adding a dish does not start cooking or reserve a kitchen slot. Ask the restaurant for current waiting times when that information is needed.

## Takeaway and packaging {#takeaway}

Takeaway is a sample service topic for this fictional restaurant; the application does not currently submit a takeaway order. A real takeaway request would need confirmation of collection arrangements, packaging and any charges with the restaurant. No packaging material, temperature-retention duration or environmental certification is verified here. The cart does not store a collection time, packaging preference or address. Do not claim these details have been recorded. For sauces or fragile items, a person may ask the restaurant about separate containers, but the assistant must not promise that those containers are available.

## Availability and substitutions {#availability-substitutions}

Use the current menu database to determine availability and price. A descriptive recommendation does not reserve a dish, and availability may change before a cart update. If a requested item is unavailable, explain that and suggest available alternatives with similar ingredients, flavour or preparation. A suggested alternative is not an automatic substitution: the person should choose it before the cart is changed. Do not silently replace a dish that has dietary or allergen implications. Prices are maintained in the menu database rather than this guide, so no price quoted from memory should override the live menu record.

## Ordering and cart behaviour {#ordering-cart}

The current application supports a guest session and its own cart. A cart line has a menu item and quantity; allowed quantities are 1 to 20, and setting quantity to 0 removes the line. Cart totals use current menu prices. The cart does not provide checkout, payment processing, delivery tracking, coupons or confirmed orders. Ending a session removes that session and its cart. A session has a fixed expiry rather than an indefinite account history. Explain the cart as a draft and do not claim an order has been placed or paid for merely because an item was added.
