package gov.bnl.channelfinder;

import static gov.bnl.channelfinder.CFResourceDescriptors.CHANNEL_RESOURCE_URI;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.servlet.ServletContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import gov.bnl.channelfinder.AuthorizationService.ROLES;

@RestController
@RequestMapping(CHANNEL_RESOURCE_URI)
@EnableAutoConfiguration
public class ChannelManager {

    static Logger channelManagerAudit = Logger.getLogger(ChannelManager.class.getName() + ".audit");
    static Logger log = Logger.getLogger(ChannelManager.class.getName());

    @Autowired
    private ServletContext servletContext;

    @Autowired
    TagRepository tagRepository;

    @Autowired
    PropertyRepository propertyRepository;

    @Autowired
    ChannelRepository channelRepository;

    @Autowired
    AuthorizationService authorizationService;

    /**
     * GET method for retrieving a collection of Channel instances, based on a
     * multi-parameter query specifying patterns for tags, property values, and
     * channel names to match against.
     *
     * @return list of all channels
     */
    @GetMapping
    public List<XmlChannel> query(@RequestParam MultiValueMap<String, String> allRequestParams) {
        return channelRepository.search(allRequestParams);
    }

    /**
     * GET method for retrieving an instance of Channel identified by
     * <tt>channelName</tt>.
     *
     * @param channelName - channel name to search for
     * @return found channel
     */
    @GetMapping("/{channelName}")
    public XmlChannel read(@PathVariable("channelName") String channelName) {
        channelManagerAudit.info("getting channel: " + channelName);

        Optional<XmlChannel> foundChannel = channelRepository.findById(channelName);
        if (foundChannel.isPresent())
            return foundChannel.get();
        else
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The channel with the name " + channelName + " does not exist");
    }

    /**
     * PUT method for creating/replacing a channel instance identified by the
     * payload. The <b>complete</b> set of properties for the channel must be
     * supplied, which will replace the existing set of properties.
     *
     * @param channelName - name of channel to be created 
     * @param channel - new data (properties/tags) for channel <tt>chan</tt>
     * @return the created channel
     */
    @PutMapping("/{channelName}")
    public XmlChannel create(@PathVariable("channelName") String channelName, @RequestBody XmlChannel channel) {
        // check if authorized role
        if(authorizationService.isAuthorizedRole(SecurityContextHolder.getContext().getAuthentication(), ROLES.CF_CHANNEL)) {
            channelManagerAudit.info("PUT:" + channel.toLog());
            // Validate request parameters
            validateChannelRequest(channel);

            // check if authorized owner
            if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), channel)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User does not have the proper authorization to perform an operation on this channel: " + channel, null);
            }
            Optional<XmlChannel> existingChannel = channelRepository.findById(channelName);
            boolean present = existingChannel.isPresent();
            if(present) {
                if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), existingChannel.get())) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "User does not have the proper authorization to perform an operation on this channel: " + existingChannel, null);
                } 
                // delete existing channel
                channelRepository.deleteById(channelName);
            } 

            // reset owners of attached tags/props back to existing owners
            channel.getProperties().forEach(prop -> prop.setOwner(propertyRepository.findById(prop.getName()).get().getOwner()));
            channel.getTags().forEach(tag -> tag.setOwner(tagRepository.findById(tag.getName()).get().getOwner()));

            // create new channel
            return channelRepository.index(channel);
        } else
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "User does not have the proper authorization to perform an operation on this channel: " + channelName, null);
    }

    /**
     * PUT method for creating multiple channels.
     *
     * @param channels - XmlChannels to be created
     * @return the list of channels created
     */
    @PutMapping
    public Iterable<XmlChannel> create(@RequestBody Iterable<XmlChannel> channels) {
        // check if authorized role
        if(authorizationService.isAuthorizedRole(SecurityContextHolder.getContext().getAuthentication(), ROLES.CF_CHANNEL)) {           
            // check if authorized owner
            for(XmlChannel channel: channels) {

                Optional<XmlChannel> existingChannel = channelRepository.findById(channel.getName());
                boolean present = existingChannel.isPresent();
                if(present) {
                    if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), existingChannel.get())) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "User does not have the proper authorization to perform an operation on this channel: " + existingChannel, null);
                    } 
                    channel.setOwner(existingChannel.get().getOwner());
                } else {
                    if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), channel)) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "User does not have the proper authorization to perform an operation on this channel: " + channel, null);
                    }
                }
            }

            // Validate request parameters
            validateChannelRequest(channels);

            // delete existing channels
            for(XmlChannel channel: channels) {
                if(channelRepository.existsById(channel.getName())) {
                    // delete existing channel
                    channelRepository.deleteById(channel.getName());
                } 
            }

            // reset owners of attached tags/props back to existing owners
            for(XmlChannel channel: channels) {
                channel.getProperties().forEach(prop -> prop.setOwner(propertyRepository.findById(prop.getName()).get().getOwner()));            
                channel.getTags().forEach(tag -> tag.setOwner(tagRepository.findById(tag.getName()).get().getOwner()));
            }

            channels.forEach(log -> {
                channelManagerAudit.info("PUT" + log.toLog());
            });

            // create new channels
            return channelRepository.indexAll(channels);
        } else
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "User does not have the proper authorization to perform an operation on these channels: " + channels, null);
    }

    /**
     * POST method for merging properties and tags of the channel identified by the
     * payload into an existing channel.
     *
     * @param channelName - name of channel to add
     * @param channel - new XmlChannel data (properties/tags) to be merged into channel <tt>channelName</tt>
     * @return the updated channel         
     */
    @PostMapping("/{channelName}")
    public XmlChannel update(@PathVariable("channelName") String channelName, @RequestBody XmlChannel channel) {
        if(authorizationService.isAuthorizedRole(SecurityContextHolder.getContext().getAuthentication(), ROLES.CF_CHANNEL)) {
            long start = System.currentTimeMillis();
            channelManagerAudit.info("|" + servletContext.getContextPath() + "|POST|validation : "
                    + (System.currentTimeMillis() - start));
            // Validate request parameters
            validateChannelRequest(channel);

            // check if authorized owner
            if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), channel)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "User does not have the proper authorization to perform an operation on this channel: " + channel, null);
            }
            Optional<XmlChannel> existingChannel = channelRepository.findById(channelName);
            boolean present = existingChannel.isPresent();
            if(present) {
                if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), existingChannel.get())) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "User does not have the proper authorization to perform an operation on this channel: " + existingChannel, null);
                }
            } 

            // reset owners of attached tags/props back to existing owners
            channel.getProperties().forEach(prop -> prop.setOwner(propertyRepository.findById(prop.getName()).get().getOwner()));
            channel.getTags().forEach(tag -> tag.setOwner(tagRepository.findById(tag.getName()).get().getOwner()));

            // update channel
            return channelRepository.save(channelName,channel);
        } else
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "User does not have the proper authorization to perform an operation on this channel: " + channelName, null);
    }

    /**
     * POST method for merging properties and tags of the Channels identified by the
     * payload into existing channels.
     *
     * @param channels - XmlChannels to be updated
     * @result the updated channels
     */
    @PostMapping()
    public Iterable<XmlChannel> update(@RequestBody Iterable<XmlChannel> channels) {
        // check if authorized role
        if(authorizationService.isAuthorizedRole(SecurityContextHolder.getContext().getAuthentication(), ROLES.CF_CHANNEL)) {
            long start = System.currentTimeMillis();

            for(XmlChannel channel: channels) {                
                Optional<XmlChannel> existingChannel = channelRepository.findById(channel.getName());
                boolean present = existingChannel.isPresent();
                if(present) {
                    if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), existingChannel.get())) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "User does not have the proper authorization to perform an operation on this channel: " + existingChannel, null);
                    }
                    channel.setOwner(existingChannel.get().getOwner());
                } else {
                    if(!authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), channel)) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "User does not have the proper authorization to perform an operation on this channel: " + channel, null);
                    }
                }
            }

            // Validate request parameters
            validateChannelRequest(channels);   

            start = System.currentTimeMillis();
            channelManagerAudit.info("|" + servletContext.getContextPath() + "|POST|validation : "
                    + (System.currentTimeMillis() - start));

            // reset owners of attached tags/props back to existing owners
            for(XmlChannel channel: channels) {
                channel.getProperties().forEach(prop -> prop.setOwner(propertyRepository.findById(prop.getName()).get().getOwner()));            
                channel.getTags().forEach(tag -> tag.setOwner(tagRepository.findById(tag.getName()).get().getOwner()));
            }

            // update channels
            return channelRepository.saveAll(channels);
        } else
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "User does not have the proper authorization to perform an operation on these channels: " + channels, null);
    }

    /**
     * DELETE method for deleting a channel instance identified by path parameter
     * <tt>channelName</tt>.
     *
     * @param channelName - name of channel to remove
     */
    @DeleteMapping("/{channelName}")
    public void remove(@PathVariable("channelName") String channelName) {
        // check if authorized role
        if(authorizationService.isAuthorizedRole(SecurityContextHolder.getContext().getAuthentication(), ROLES.CF_CHANNEL)) {
            channelManagerAudit.info("deleting ch:" + channelName);
            Optional<XmlChannel> existingChannel = channelRepository.findById(channelName);
            if(existingChannel.isPresent()) {
                // check if authorized owner
                if(authorizationService.isAuthorizedOwner(SecurityContextHolder.getContext().getAuthentication(), existingChannel.get())) {
                    // delete channel
                    channelRepository.deleteById(channelName);
                } else {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "User does not have the proper authorization to perform an operation on this channel: " + channelName, null);
                }
            } else {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The tag with the channel " + channelName + " does not exist");
            }
        } else
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "User does not have the proper authorization to perform an operation on this channel: " + channelName, null);
    }

    /**
     * Check that the existing channel and the channel in the request body match
     * 
     * @param existing
     * @param request
     * @return
     */
    boolean validateChannel(XmlChannel existing, XmlChannel request) {
        return existing.getName().equals(request.getName());
    }

    /**
     * Checks if
     * 1. the channel name is not null and matches the name in the body
     * 2. the channel owner is not null or empty
     * 3. all the listed tags/props exist and prop value is not null or empty
     * 
     * @param data
     */
    public void validateChannelRequest(XmlChannel channel) {
        // 1 
        if (channel.getName() == null || channel.getName().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The channel name cannot be null or empty " + channel.toString(), null);
        }
        // 2
        if (channel.getOwner() == null || channel.getOwner().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The channel owner cannot be null or empty " + channel.toString(), null);
        }
        // 3 
        List <String> tagNames = channel.getTags().stream().map(XmlTag::getName).collect(Collectors.toList());
        for(String tagName:tagNames) {
            if(!tagRepository.existsById(tagName)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The tag with the name " + tagName + " does not exist");
            }
        }
        // 3 
        List <String> propertyNames = channel.getProperties().stream().map(XmlProperty::getName).collect(Collectors.toList());
        List <String> propertyValues = channel.getProperties().stream().map(XmlProperty::getValue).collect(Collectors.toList());
        for(String propertyName:propertyNames) {
            if(!propertyRepository.existsById(propertyName)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The property with the name " + propertyName + " does not exist");
            } 
        }
        for(String propertyValue:propertyValues) {
            if(propertyValue == null || propertyValue.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The property with the name " + propertyNames.get(propertyValues.indexOf(propertyValue)) + " has value " + propertyValue + " is null or empty");
            }
        }

    }

    /**
     * Checks if
     * 1. the tag names are not null
     * 2. the tag owners are not null or empty
     * 3. all the channels exist
     * 
     * @param data
     */
    public void validateChannelRequest(Iterable<XmlChannel> channels) {
        for(XmlChannel channel: channels) {
            validateChannelRequest(channel);
        }
    }
}
