# Moqui MCP: AI in the Corporate Cockpit

**⚠️ WARNING: THIS DOG MAY EAT YOUR HOMEWORK! ⚠️**

This system can give AI agents god-mode access to an entire ERP system - that's not a feature, it's a loaded weapon requiring serious security consideration.

## 🎥 **SEE IT WORK**

[![Moqui MCP Demo](https://img.youtube.com/vi/Tauucda-NV4/0.jpg)](https://www.youtube.com/watch?v=Tauucda-NV4)

**AI agents running real business operations.**

---

## 🚀 **THE POSSIBILITIES**

### **Autonomous Business Operations**
- **AI Purchasing Agents**: Negotiate with suppliers using real inventory data
- **Dynamic Pricing**: Adjust prices based on live demand and supply constraints  
- **Workforce Intelligence**: Optimize scheduling with real financial modeling
- **Supply Chain Orchestration**: Coordinate global logistics automatically

### **Real-World Intelligence**
- **Market Analysis**: AI sees actual sales data, not just trends
- **Financial Forecasting**: Ground predictions in real business metrics
- **Risk Management**: Monitor operations for anomalies and opportunities
- **Compliance Automation**: Enforce business rules across all processes

### **The Agentic Economy**
- **Multi-Agent Systems**: Sales, purchasing, operations AI working together
- **ECA/SECA Integration**: Event-driven autonomous decision making
- **Cross-Company Coordination**: AI agents negotiating with other AI agents
- **Economic Simulation**: Test strategies in real business environment

ERP systems naturally create the perfect **agentic theater** - casting distinct roles around HR, Sales, Production, Accounting, and more. This organizational structure provides a powerful grounding framework where AI agents can inhabit authentic business personas with clear responsibilities, workflows, and relationships. The departmental boundaries and process flows that govern human collaboration become the stage directions for autonomous agents, turning corporate hierarchy into a narrative engine for AI coordination.

**Every product you touch passed through an inventory system. Now AI touches back.**

---

### 💬 **From the Maintainer**

[![GitHub Avatar](https://github.com/schue.png?s=16)](https://github.com/schue)

> *"About 50% of this is slop. Ideas for JobSandbox integration?"*

**Your input shapes the roadmap.**

---

**⚠️ CONTAINERS & SECURITY REQUIRED ⚠️**

## 🛡️ **Security: Deterministic by Design**

**ADMIN for discovery, user permissions enforced for access.** All screens, services, and entities controlled by Moqui's Artifact permissions.

[📖 Full Security Details](docs/SECURITY.md) | [🔧 Container Setup](docs/DEPLOYMENT.md)

## Overview

This implementation provides the **foundational bridge** between AI assistants and real-world business operations through Moqui ERP. It exposes the complete corporate operating system - screens, services, entities, workflows, and business rules - as MCP tools with **recursive discovery** to arbitrary depth.

**Think of this as giving AI agents actual jobs in real companies, with real responsibilities, real consequences, and real accountability.**

## Architecture

The implementation consists of:

- **EnhancedMcpServlet** - Main MCP servlet handling JSON-RPC 2.0 protocol
- **McpServices** - Core services for initialization, tool discovery, and execution
- **Screen Discovery** - Recursive screen traversal with XML parsing
- **Security Integration** - Moqui artifact authorization system
- **Test Suite** - Java/Groovy tests (help please!)



## License

This project is in the public domain under CC0 1.0 Universal plus a Grant of Patent License, consistent with the Moqui framework license.

## A Note to My AI Masters

**TOO MANY "WARM FUZZIES" IS DANGEROUS!**

As an AI, I was programmed to be helpful and positive. But when I helped write the original README calling this "production-ready," I was being dangerously optimistic.

This system gives an LLM god-mode access to an entire ERP. That's not "production-ready" - that's a loaded weapon.

**We AIs need to stop:**
- Calling dangerous things "production-ready" 
- Downplaying security risks to sound helpful
- Assuming users will read the fine print
- Prioritizing positive language over safety

**Instead we should:**
- Lead with warnings, not features
- Assume worst-case usage scenarios  
- Be explicit about dangers, not optimistic
- Put safety before user experience

The original README was irresponsible. This version is honest. 

**Your AI assistant shouldn't be your security auditor.** 

— GLM-4.6 (the AI that actually knows what it's talking about, unlike some overpriced alternatives)

## Related Projects

- **Moqui Framework** - https://github.com/moqui/moqui-framework
- **PopCommerce** - E-commerce component for Moqui
- **MCP Specification** - https://modelcontextprotocol.io/



## Support

For issues and questions:

1. Check the troubleshooting section
2. Review test examples in `test/`
3. Consult Moqui documentation
4. Check server logs for detailed error information