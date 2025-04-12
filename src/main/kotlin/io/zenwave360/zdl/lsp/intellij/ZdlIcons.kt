package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icons for ZDL
 */
object ZdlIcons {
    /**
     * Icon for ZDL files
     */
    val FILE: Icon = IconLoader.getIcon("/icons/zdl.svg", ZdlIcons::class.java)
    
    /**
     * Icon for entities
     */
    val ENTITY: Icon = IconLoader.getIcon("/icons/entity.svg", ZdlIcons::class.java)
    
    /**
     * Icon for enums
     */
    val ENUM: Icon = IconLoader.getIcon("/icons/enum.svg", ZdlIcons::class.java)
    
    /**
     * Icon for services
     */
    val SERVICE: Icon = IconLoader.getIcon("/icons/service.svg", ZdlIcons::class.java)
    
    /**
     * Icon for aggregates
     */
    val AGGREGATE: Icon = IconLoader.getIcon("/icons/aggregate.svg", ZdlIcons::class.java)
    
    /**
     * Icon for events
     */
    val EVENT: Icon = IconLoader.getIcon("/icons/event.svg", ZdlIcons::class.java)
}
