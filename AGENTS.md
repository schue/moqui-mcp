# Moqui MCP Self-Guided Narrative Screens

## 🎯 Core Goal

Enable ANY AI/LLM model to autonomously navigate Moqui ERP and perform business tasks through **self-guided narrative screens** that provide:
- Clear description of current state
- Available actions with exact invocation examples
- Navigation guidance for related screens
- Contextual notes for constraints and next steps

The interface is **model-agnostic** - works with GPT, Claude, local models, or any other AI agent.

---

## 🧩 How Models Use the Interface

### Discovery Workflow
```
1. moqui_browse_screens(path="") → See available screens
2. moqui_get_screen_details(path="/PopCommerce/Catalog/Product") → Understand parameters
3. moqui_render_screen(path="/PopCommerce/Catalog/Product/FindProduct", parameters={name: "blue widget"}) → Execute with context
```

### Navigation Pattern
```
AI receives: "Find blue products in catalog"
→ Browse to /PopCommerce/Catalog
→ See subscreen: Product/FindProduct
→ uiNarrative.actions: "To search products, use moqui_render_screen(path='/PopCommerce/Catalog/Product/FindProduct', parameters={productName: 'blue'})"
→ AI executes exactly as guided
```

### Action Execution Pattern
```
AI receives: "Update PROD-001 price to $35.99"
→ Browse to /PopCommerce/Catalog/Product/EditPrices
→ uiNarrative.actions: "To update price, call with action='update', parameters={productId: 'PROD-001', price: 35.99}"
→ AI executes transition
→ Receives confirmation
→ Reports completion
```

---

## 🔧 Near-Term Fixes (Required for Generic Model Access)

### ✅ 1. Path Delimiter Change (COMPLETED)
**Goal**: Change from `.` to `/` to match browser URLs
**Impact**: More intuitive, matches what users see in browser
**Priority**: High

**Files modified**:
- ✅ `service/McpServices.xml` - Path resolution logic (line 206) - Now supports both `.` and `/`
- ✅ `screen/macro/DefaultScreenMacros.mcp.ftl` - Link rendering (line 70) - Links now use `/`
- ✅ `data/McpScreenDocsData.xml` - All documentation examples updated to `/`
- ✅ `data/McpPromptsData.xml` - Prompt examples updated to `/`

**Changes made**:
- Split path on `/` first, fallback to `.` for backward compatibility
- Updated all navigation links in macros to use `/` delimiter
- Updated all wiki documentation to use `/` format

**Backward compatibility**: Both `.` and `/` delimiters work during transition period

### ✅ 2. Screen Path Resolution Fix (COMPLETED)
**Problem**: PopCommerce screens return empty responses
**Impact**: ANY model cannot access core business screens
**Priority**: Critical

**Files modified**:
- ✅ `service/McpServices.xml` (lines 1521-1545) - Fixed Admin vs Root fallback
- ✅ `service/McpServices.xml` (lines 952-962) - Better error messages for navigation failures

**Changes made**:
- Added debug logging for all path resolution attempts
- Fixed fallback logic to try PopCommerceAdmin first, then PopCommerceRoot
- Added specific error messages when navigation fails
- Added logging of available subscreens on failure

**Validation required**:
- [ ] PopCommerce.PopCommerceAdmin screens render with data (test with server running)
- [ ] PopCommerce.PopCommerceRoot screens render with data
- [ ] Error messages show which screen failed and why
- [ ] Deep screens (FindProduct, EditPrices) render correctly

### ✅ 3. Dynamic Service Name Resolution (COMPLETED)
**Problem**: Hardcoded to `mantle.product.ProductPrice`
**Impact**: ANY model limited to pricing, cannot create orders/customers
**Priority**: Critical

**Files modified**:
- ✅ `service/McpServices.xml` (lines 1649-1712) - Dynamic service extraction from transitions

**Changes made**:
- Extract service names from transition definitions dynamically
- Fallback to convention only when transition has no service
- Added logging of found transitions
- Added error message when service not found

**Validation required**:
- [ ] Price updates work (ProductPrice)
- [ ] Order creation works (mantle.order.Order)
- [ ] Customer creation works (mantle.party.Party)
- [ ] All entity types supported dynamically

### ✅ 4. Parameter Validation (COMPLETED)
**Problem**: Actions execute without checking requirements
**Impact**: ANY model receives cryptic errors on invalid calls
**Priority**: High

**Files modified**:
- ✅ `service/McpServices.xml` (lines 1667-1712, 1703-1731) - Validation before service calls

**Changes made**:
- Added service definition lookup before execution
- Validate all required parameters exist (collects ALL missing params)
- Return clear error message listing all missing parameters
- Log which parameters are missing for debugging

**Validation required**:
- [ ] Missing parameters return clear error before execution
- [ ] All missing parameters listed together in error message
- [ ] Optional parameters still work
- [ ] No silent failures

### ✅ 5. Transition Metadata Enhancement (COMPLETED)
**Problem**: Only name and service captured, missing requirements
**Impact**: ANY model doesn't know what parameters are needed
**Priority**: High

**Files modified**:
- ✅ `service/McpServices.xml` (lines 1000-1017) - Enhanced action metadata

**Changes made**:
- Extract full service definitions from transition
- Include parameter names, types, and required flags
- Add parameter details to action metadata
- Handle cases where service has no parameters

**Validation required**:
- [ ] Actions include parameter names and types
- [ ] Required/optional flags included
- [ ] Models can determine what's needed before calling
- [ ] UI narrative includes this metadata

### 6. Screen Navigation Error Handling (COMPLETED)
**Problem**: Silent failures in deep screens
**Impact**: ANY model cannot reach important business functions
**Priority**: Medium

**Files modified**:
- ✅ `service/McpServices.xml` (lines 952-962) - Specific error messages

**Changes made**:
- Added specific error messages when navigation fails
- Log which segment failed in path
- Log available subscreens on failure
- Prevent silent failures

**Validation required**:
- [ ] Navigation failures show which segment failed
- [ ] Error lists available subscreens
- [ ] Models can navigate to correct screen
- [ ] No silent failures

---

## ✅ Implementation Status Summary

### Phase 1: Documentation ✅
- [x] AGENTS.md created
- [x] Wiki documentation updated to use `/` delimiter
- [x] All path examples updated

### Phase 2: Near-Term Fixes ✅
- [x] Path delimiter changed to `/` (backward compatible)
- [x] Screen path resolution fixed with Admin vs Root distinction
- [x] Dynamic service name resolution implemented
- [x] Parameter validation added (collects all errors)
- [x] Transition metadata enhanced with parameter details
- [x] Screen navigation error handling improved

### Phase 3: Validation & Testing (PENDING)
- [ ] Server restart required to load changes
- [ ] Screen rendering tests run manually
- [ ] Transition execution tests run manually
- [ ] Path delimiter tests run manually
- [ ] Model-agnostic tests run (if models available)

---

## ✅ Validation: Generic Model Access

### Screen Rendering Tests (Requires server restart)
- [ ] Root screens (PopCommerce, SimpleScreens) render with uiNarrative
- [ ] Admin subscreens (Catalog, Order, Customer) accessible
- [ ] FindProduct screen renders with search form
- [ ] EditPrices screen renders with product data
- [ ] FindOrder screen renders with order data
- [ ] All screens have semantic state with forms/lists
- [ ] UI narratives are clear and actionable

### Transition Execution Tests (Requires server restart)
- [ ] Create actions work for all entity types
- [ ] Update actions work for all entity types
- [ ] Delete actions work where applicable
- [ ] Form submissions process parameters correctly
- [ ] Parameter validation catches missing fields
- [ ] Invalid parameters return helpful errors

### Path Delimiter Tests (Requires server restart)
- [ ] `/PopCommerce/PopCommerceAdmin/Catalog/Product` works
- [ ] `PopCommerce.PopCommerceAdmin.Catalog.Product` still works (backward compat)
- [ ] Navigation links use `/` in output
- [ ] Error messages reference paths with `/`
- [ ] Documentation updated to use `/`

### Model Agnostic Tests (If possible)
- [ ] Screens work with any model (test with 2-3 if available)
- [ ] UI narrative provides sufficient guidance for autonomous action
- [ ] Errors are clear regardless of model choice
- [ ] No model-specific code or assumptions

### End-to-End Business Tasks (Requires server restart)
**Test with multiple models to ensure generic access:**
- [ ] Product search (any query pattern)
- [ ] Price update (any product, any price)
- [ ] Customer lookup (any customer identifier)
- [ ] Order creation (any customer, any product)
- [ ] Order status check (any order ID)
- [ ] Multi-step workflows (browse → execute → verify)

---

## 📊 Success Metrics

### Narrative Quality
- **Coverage**: 100% of screens should have uiNarrative
- **Clarity**: Models can understand current state from 50-80 word descriptions
- **Actionability**: Models have exact tool invocation examples for all actions
- **Navigation**: Models can navigate hierarchy independently

### Functional Coverage
- **Screen Access**: All documented screens should render successfully
- **Transition Types**: All action patterns (create, update, delete, submit) should work
- **Entity Coverage**: Should work across Product, Order, Customer, Inventory entities
- **Error Handling**: Clear, actionable error messages for all failure modes

### Model Agnosticism
- **Provider Independence**: Works with OpenAI, Anthropic, local models
- **Size Independence**: Effective for 7B models and 70B models
- **Input Flexibility**: Handles various natural language phrasings
- **Output Consistency**: Reliable responses regardless of model choice

---

## 🧪 Use Cases (Not Exhaustive)

### Human-in-the-Loop
- User: "Help me find products"
- Model: Screens for browsing, narrows to products, presents options
- User: Selects product, asks for price change
- Model: Executes price update, confirms
- User: Reviews and approves

### External AI Integration
- External system: "Create order for customer CUST-001: 5 units of PROD-002"
- HTTP API to Moqui MCP
- MCP: Executes order creation
- Returns: Order ID and confirmation
- External system: Confirms and updates records

### Manual Model Testing
- Developer: Runs model through MCP interface
- Model: Navigates screens, performs tasks
- Developer: Observes behavior, validates output
- Developer: Adjusts UI narrative or transition logic based on model struggles

---

## 🚀 Future Enhancements

Beyond core narrative screens:
- Multi-agent coordination via notifications
- Context retention across sessions
- Proactive suggestions
- Advanced workflow orchestration
- Agent that monitors notifications and executes tasks autonomously
