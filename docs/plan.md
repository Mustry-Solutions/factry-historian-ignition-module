# Plan


## 5 days research

### 1 day
 [+] - scaffolding ignition module 

### 2 day
 [ ] - examine Canary ignition module 
 [ ] - finding the interesting entrypoints in Ignition Api
### 3 day 
 [ ] - understanding the Factry functionality and implementation challanges 
### 4 day
 [ ] - proof of concept
     [ ] factry historian is selectable from the historian lists
     [ ] writing: tag changes in tagbrowser -> check deadband -> api call from the module -> outside collector reciever prints to console
     [ ] reading: request historical data -> module request to outside provider -> data displayed on chart

### 5 day
 [ ] - plan and estimate implementation


## Feature set

Factry historian module should implement:

 - Two separate components: A Collector (Storage Provider) and a Provider (History Provider)
 - Local service architecture: Consider a local buffer/forward service on the Ignition Gateway for resilience
 - Web API communication: RESTful APIs for both writing and reading data
 - Integration with Tag History Module: Leverage Ignition's built-in history configuration (deadbands, sample modes, etc.)
 - DataSet/grouping concept: Logical organization of tags
 - Standard provider interface: So Ignition components can query your historian like any other

