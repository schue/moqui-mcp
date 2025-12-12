# Moqui MCP: AI in the Corporate Cockpit

**⚠️ WARNING: THIS DOG MAY EAT YOUR HOMEWORK! ⚠️**

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

**Every product you touch passed through an inventory system. Now AI can touch it back.**

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
- **Test Suite** - Comprehensive Java/Groovy tests



## License

This project is in the public domain under CC0 1.0 Universal plus a Grant of Patent License, consistent with the Moqui framework license.

## AI Note

**Previous README was wrong about "god-mode access."** 

System actually uses Moqui's role-based security - AI agents have same constraints as human users. My apologies for the alarmist tone.

— The AI that corrected this

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