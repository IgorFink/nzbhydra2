### Hosting
If you use Hydra behind a reverse proxy you might want to set the URL base to a value like "/nzbhydra". 

If you accesses Hydra with tools running outside your network (for example from your phone) set the external URL so that it matches the full Hydra URL. 
That way the NZB links returned in the search results refer to your global URL and not your local address.

You can use SSL but I recommend using a reverse proxy with SSL. See [the wiki](https://github.com/theotherp/nzbhydra/wiki/Reverse-proxies-and-URLs). TODO

### Security
Erase the API key to disable authentication by API key. Some tools might not even support that, so better leave it there, even if you use Hydra only locally.
 
### Logging
The base settings should suffice for most users. If you want you can enable logging of IP adresses for failed logins and NZB downloads. 
 

