# Factry Historian Module - Documentation Index

## Context and Background

- [Specification](specification.md) - Factry historian module specification
- [Questions](Questions.md) - Technical questions for Inductive Automation forum regarding historian module limitations
- [Notes](notes.md) - Quick notes on proxy architecture, gRPC, protobuf, and token-based historian config
- [Report](report.md) - Development report (Nov 4, 2025) summarizing 4 days of research
- [Forum Post](FORUM_POST.md) - Inductive Automation community post on custom historian module implementation

## Overview, Architecture and Design

- [Design Document](design_document.md) - Comprehensive design covering SDK, data flow, and communication protocols
- [Architecture](architecture.md) - Architecture overview diagram of the module structure and components
- [Module Structure](module.md) - Visual diagram of the Ignition module structure and relationships
- [Feasibility Study Findings](feasibility_study_findings.md) - Feasibility study confirming SDK 8.3.1 support for custom historian development
- [Proof of Concept](PROOF_OF_CONCEPT.md) - POC guide showing deployed module status and testing goals

## Module Development

- [Historian Registration 8.3](historian_registration_8.3.md) - Registering the Factry Historian as a HistorianExtensionPoint
- [Web UI Implementation](web_ui_implementation.md) - Historian configuration web UI component implementation
- [Programmatic Historian Workaround](programmatic_historian_workaround.md) - Creating historian instances programmatically without UI registration
- [Module Deployment](module_deployment.md) - Analysis of module caching issues with Docker volume mounts
- [Module Update Workflow](module_update_workflow.md) - Verified procedure for updating the module using Docker volume mounting
- [Module Update Problem](module_update_problem.md) - Runtime issue where compiled code changes weren't reflected during execution

## Ignition Related

- [Ignition 8.3 Historian API Research](ignition_8.3_historian_api_research.md) - Research on the new Historian API introduced in Ignition 8.3.0
- [Ignition Historian Module Development](ignition_historian_module_development.md) - Comprehensive guide for building custom historian modules for Ignition 8.3+
- [Ignition Module Development](ignition_module_development.md) - Quick reference for historian module development with SDK JavaDocs links
- [Workaround: Tag Configuration](WORKAROUND_TAG_CONFIGURATION.md) - Configuring tags with FactryHistorian via Gateway scripts
- [Historian from Jython](historian_from_jython.md) - Testing the historian directly via Jython scripts without using tags
